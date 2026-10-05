package com.dragosfotache.gymtracka.data.sync.store

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.encodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.codec.requiredInt
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/**
 * Inert Room-backed push-claim boundary. Serializes one account/session run,
 * selects the explicit sequence head, freezes it durably before returning its
 * exact request, and fences replay/late callback admission. A future caller can
 * perform HTTP outside Room while holding this run's ownership.
 *
 * This slice performs no HTTP, retry loops or engine work. R06b adds single-head
 * outcome classification with durable rejection, invalid-response, size and
 * retry/error persistence plus bounded backoff scheduling under the same
 * claim/session/cache fencing. R06c adds atomic single-head acknowledgment
 * committing the complete accepted canonical batch, exact frozen removal and
 * generation-scoped metadata acknowledgment in one fenced transaction.
 * Returning a claim never means delivered or converged. Production constructs
 * neither this store nor an engine.
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

    /** Serializes handling admission and consumed-claim checks within one run. */
    private val handlingMutex = Mutex()

    /**
     * Conservative process-local retry token for claims entering a bookkeeping
     * write. Guarded by [handlingMutex]. PushClaim uses referential identity,
     * so this rejects exactly the consumed callback object. The token is taken
     * when entering the write (after exact pre-write head/fences), before the
     * DAO update, and is never released on rollback or uncertain commit: a
     * backward-clock transient reschedule can leave the row byte-identical,
     * and Room can lose the result on canceled resumption after commit, so row
     * comparison alone cannot consume the claim. This is not persistence or
     * acknowledgment evidence: the handler still throws the original
     * cancellation/storage/fence failure, storage rolls back if uncommitted,
     * and the caller must reclaim the same frozen identity for a fresh
     * admission before another handling attempt. Read-only accepted and
     * authentication paths never take the token.
     */
    private val consumedClaims = mutableSetOf<PushClaim>()

    /**
     * The run's one current pull-checkpoint token, or null when no checkpoint is
     * admitted. Guarded by [handlingMutex]. Tokens are fresh objects that are never
     * reused, so clearing this field retires every prior token forever: a new read
     * attempt clears it (even on a failed read) and a batch commit clears it
     * immediately before any write. Read-only checks never clear. Under the
     * handling Mutex, referential comparison against this field alone decides
     * whether a token is current.
     */
    private var currentPullCheckpoint: PullCheckpoint? = null

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
            handlingMutex.withLock {
                if (claim in consumedClaims) {
                    throw StalePushClaimException()
                }
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
            }
            currentCoroutineContext().ensureActive()
        } catch (_: SQLiteException) {
            currentCoroutineContext().ensureActive()
            throw PushStorageException()
        }
    }

    /**
     * Classifies one complete single-head push response and durably records
     * rejection or invalid-response retention under exact claim/session/cache
     * fencing. Accepted and duplicate-accepted outcomes are read-only: they
     * preserve status and the complete batch, change nothing (including retry
     * fields), and never call CanonicalStore or delete the row. All writes run
     * in one Room transaction that rechecks live admission, fences and the
     * exact head before the write and fences/admission plus the expected
     * post-write head before commit. Performs no HTTP and no delays.
     */
    suspend fun handleResult(claim: PushClaim, result: PushResultModel): PushOutcomeResult {
        val owned = try {
            decodePushResult(encodePushResult(result))
        } catch (_: SyncModelException) {
            null
        } catch (_: JSONException) {
            null
        }
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
        val decision = classifyPushResult(claim, owned)
        return handlingMutex.withLock {
            if (claim in consumedClaims) {
                throw StalePushClaimException()
            }
            try {
                val outcome = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    val head = database.syncDao().getOutboxHead(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (head != claim.row || claim in consumedClaims) {
                        throw StalePushClaimException()
                    }
                    when (decision) {
                        is PushResultDecision.Accepted -> {
                            currentCoroutineContext().ensureActive()
                            PushOutcomeResult.Accepted(
                                decision.outcome,
                                claim.accountId,
                                claim.row.copy(),
                            )
                        }
                        is PushResultDecision.Rejected -> {
                            persistClaimOutcome(
                                claim,
                                newState = "rejected",
                                errorCode = decision.errorCode,
                                errorMessage = PUSH_REJECTED_MESSAGE,
                                retryAfterMillis = null,
                            )
                            PushOutcomeResult.Rejected(decision.errorCode)
                        }
                        is PushResultDecision.Malformed -> {
                            persistClaimOutcome(
                                claim,
                                newState = "frozen",
                                errorCode = MALFORMED_RESPONSE_CODE,
                                errorMessage = PUSH_MALFORMED_MESSAGE,
                                retryAfterMillis = null,
                            )
                            PushOutcomeResult.Malformed
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                outcome
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PushStorageException()
            }
        }
    }

    /**
     * Records one push transport failure for the exact claimed head: transient
     * faults schedule a bounded absolute retry, size/rejection faults retain a
     * safe frozen error, and authentication failures are read-only. All writes
     * run in one fenced Room transaction with the same admission/head/post-write
     * rechecks as [handleResult]. Performs no HTTP and no delays.
     */
    suspend fun handleFailure(
        claim: PushClaim,
        failure: SyncApiException,
        nowMillis: Long,
    ): PushFailureResult {
        if (nowMillis < 0) {
            throw IllegalArgumentException("Push failure time must be nonnegative")
        }
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
        val transientRetryAt = if (failure is SyncApiException.Transient) {
            pushRetryAtMillis(nowMillis, claim.row.attemptCount, failure.retryAfterMillis)
        } else {
            null
        }
        return handlingMutex.withLock {
            if (claim in consumedClaims) {
                throw StalePushClaimException()
            }
            try {
                val outcome = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    val head = database.syncDao().getOutboxHead(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (head != claim.row || claim in consumedClaims) {
                        throw StalePushClaimException()
                    }
                    when (failure) {
                        is SyncApiException.Transient -> {
                            val retryAt = transientRetryAt
                            if (retryAt == null) {
                                persistClaimOutcome(
                                    claim,
                                    newState = "frozen",
                                    errorCode = RETRY_TIME_EXHAUSTED_CODE,
                                    errorMessage = PUSH_RETRY_EXHAUSTED_MESSAGE,
                                    retryAfterMillis = null,
                                )
                                PushFailureResult.Retained(RETRY_TIME_EXHAUSTED_CODE)
                            } else {
                                persistClaimOutcome(
                                    claim,
                                    newState = "frozen",
                                    errorCode = null,
                                    errorMessage = null,
                                    retryAfterMillis = retryAt,
                                )
                                PushFailureResult.RetryScheduled(retryAt)
                            }
                        }
                        is SyncApiException.AuthenticationRequired -> {
                            currentCoroutineContext().ensureActive()
                            PushFailureResult.AuthenticationRequired
                        }
                        is SyncApiException.ReauthenticationRequired -> {
                            currentCoroutineContext().ensureActive()
                            PushFailureResult.ReauthenticationRequired
                        }
                        is SyncApiException.RequestTooLarge -> {
                            persistClaimOutcome(
                                claim,
                                newState = "frozen",
                                errorCode = REQUEST_TOO_LARGE_CODE,
                                errorMessage = PUSH_TOO_LARGE_MESSAGE,
                                retryAfterMillis = null,
                            )
                            PushFailureResult.Retained(REQUEST_TOO_LARGE_CODE)
                        }
                        is SyncApiException.RequestRejected -> {
                            val code = failure.errorCode
                            if (code.isBlank()) {
                                persistClaimOutcome(
                                    claim,
                                    newState = "frozen",
                                    errorCode = MALFORMED_RESPONSE_CODE,
                                    errorMessage = PUSH_MALFORMED_MESSAGE,
                                    retryAfterMillis = null,
                                )
                                PushFailureResult.Retained(MALFORMED_RESPONSE_CODE)
                            } else {
                                persistClaimOutcome(
                                    claim,
                                    newState = "frozen",
                                    errorCode = code,
                                    errorMessage = PUSH_REQUEST_REJECTED_MESSAGE,
                                    retryAfterMillis = null,
                                )
                                PushFailureResult.Retained(code)
                            }
                        }
                        is SyncApiException.MalformedResponse,
                        is SyncApiException.InvalidCursor,
                        is SyncApiException.CursorExpired,
                        is SyncApiException.InvalidSnapshotToken,
                        is SyncApiException.SnapshotExpired,
                        -> {
                            persistClaimOutcome(
                                claim,
                                newState = "frozen",
                                errorCode = MALFORMED_RESPONSE_CODE,
                                errorMessage = PUSH_MALFORMED_MESSAGE,
                                retryAfterMillis = null,
                            )
                            PushFailureResult.Retained(MALFORMED_RESPONSE_CODE)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                outcome
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PushStorageException()
            }
        }
    }

    /**
     * Atomically acknowledges one complete accepted canonical batch for the exact
     * claimed head: applies the whole batch with R05b arbitration/family
     * validation, acknowledges generation-scoped local root metadata, removes
     * only the selected exact frozen row, and marks the account projection dirty.
     * The accepted snapshot is data, not write authority: its private origin
     * provenance, mutation binding, status, batch and envelope target are owned
     * and revalidated before the first suspension, and every field of the exact
     * current explicit head plus all live fences is rechecked inside Room before
     * writes. Invalid local context retains the exact frozen head with a fixed
     * safe error and returns RetainedCorruption without canonical/domain/cache
     * writes. Performs no HTTP and claims no successor.
     */
    suspend fun acknowledgeAccepted(
        claim: PushClaim,
        accepted: PushOutcomeResult.Accepted,
    ): PushAcknowledgmentResult {
        val ownedBatch = validatedAcceptedBatch(claim, accepted)
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
        return handlingMutex.withLock {
            if (claim in consumedClaims) {
                throw StalePushClaimException()
            }
            try {
                val outcome = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    val head = database.syncDao().getOutboxHead(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (head != claim.row || claim in consumedClaims) {
                        throw StalePushClaimException()
                    }
                    val effects = decodeDurableRequest(
                        parseStrictJsonObject(claim.row.payloadJson),
                    ).affectedRoots
                    val outboxBefore = database.syncDao().getOutboxForAccount(accountId)
                    when (val context = validateAckContext(database, accountId, effects)) {
                        is AckContext.Invalid -> {
                            persistClaimOutcome(
                                claim,
                                newState = "frozen",
                                errorCode = ACK_CONTEXT_CORRUPT_CODE,
                                errorMessage = ACK_CONTEXT_CORRUPT_MESSAGE,
                                retryAfterMillis = null,
                            )
                            PushAcknowledgmentResult.RetainedCorruption
                        }
                        is AckContext.Valid -> {
                            // Fail-closed retry token: consumed before any
                            // canonical/metadata/removal write attempt and never
                            // released, so rollback or uncertain commit still
                            // requires fresh admission of the same frozen identity.
                            consumedClaims.add(claim)
                            val canonical = applyOwnedCanonicalBatch(
                                database,
                                accountId,
                                sessionGeneration,
                                expectedCacheEpoch,
                                ownedBatch,
                            )
                            currentCoroutineContext().ensureActive()
                            requireAdmitted()
                            val (acknowledgedRoots, expectations) = applyAckMetadataUpdates(
                                database,
                                accountId,
                                context.roots,
                            )
                            val preRemove = database.syncDao().getOutboxMutation(
                                claim.row.mutationId,
                                accountId,
                            )
                            currentCoroutineContext().ensureActive()
                            requireAdmitted()
                            if (preRemove != claim.row) {
                                throw StalePushClaimException()
                            }
                            val deleted = database.syncDao().deleteExactFrozenHead(
                                claim.row.mutationId,
                                accountId,
                                claim.row.state,
                                claim.row.attemptCount,
                            )
                            if (deleted != 1) {
                                throw IllegalStateException("Push head changed during transaction")
                            }
                            if (database.syncDao().getOutboxMutation(
                                    claim.row.mutationId,
                                    accountId,
                                ) != null
                            ) {
                                throw IllegalStateException("Push head changed during transaction")
                            }
                            database.syncCanonicalDao().insertAccountCache(accountId)
                            if (database.syncCanonicalDao().markProjectionDirty(accountId) != 1) {
                                throw IllegalStateException("Account cache changed during transaction")
                            }
                            recheckAckMetadata(database, expectations)
                            recheckAckWatermarks(database, accountId, context.roots)
                            val outboxAfter = database.syncDao().getOutboxForAccount(accountId)
                            if (outboxAfter.size != outboxBefore.size - 1 ||
                                outboxAfter.any { it.mutationId == claim.row.mutationId }
                            ) {
                                throw IllegalStateException("Push head changed during transaction")
                            }
                            val afterById = outboxAfter.associateBy { it.mutationId }
                            for (row in outboxBefore) {
                                if (row.mutationId == claim.row.mutationId) {
                                    continue
                                }
                                if (afterById[row.mutationId] != row) {
                                    throw IllegalStateException("Push head changed during transaction")
                                }
                            }
                            if (database.syncDao().getOutboxHead(accountId) == claim.row) {
                                throw IllegalStateException("Push head changed during transaction")
                            }
                            currentCoroutineContext().ensureActive()
                            requireAdmitted()
                            checkCanonicalFences(
                                database,
                                accountId,
                                sessionGeneration,
                                expectedCacheEpoch,
                            )
                            currentCoroutineContext().ensureActive()
                            PushAcknowledgmentResult.Acknowledged(canonical, acknowledgedRoots)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                outcome
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PushStorageException()
            }
        }
    }

    /**
     * Applies one outcome write inside the caller's active fenced transaction:
     * takes the conservative consumed-claim token on entry, updates exactly the
     * claimed row, then rechecks live admission, fences and the expected
     * post-write head before commit. Assumes the caller already verified
     * fences and the exact pre-write head. The token is never released, so a
     * rolled-back or uncertain attempt still requires fresh admission.
     */
    private suspend fun persistClaimOutcome(
        claim: PushClaim,
        newState: String,
        errorCode: String?,
        errorMessage: String?,
        retryAfterMillis: Long?,
    ) {
        if (!database.inTransaction()) {
            throw IllegalStateException("Push outcome requires an active transaction")
        }
        // Fail-closed retry token: entering the bookkeeping write consumes the
        // callback capability before the DAO update. Never released, so even a
        // rolled-back or uncertain commit requires fresh admission.
        consumedClaims.add(claim)
        val updated = database.syncDao().updatePushOutcome(
            mutationId = claim.row.mutationId,
            accountId = accountId,
            expectedState = claim.row.state,
            expectedAttempts = claim.row.attemptCount,
            newState = newState,
            errorCode = errorCode,
            errorMessage = errorMessage,
            retryAfterMillis = retryAfterMillis,
        )
        if (updated != 1) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val expected = claim.row.copy(
            state = newState,
            errorCode = errorCode,
            errorMessage = errorMessage,
            retryAfterMillis = retryAfterMillis,
        )
        val headAfter = database.syncDao().getOutboxHead(accountId)
        currentCoroutineContext().ensureActive()
        requireAdmitted()
        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
        currentCoroutineContext().ensureActive()
        if (headAfter != expected) {
            throw IllegalStateException("Push head changed during transaction")
        }
    }

    /**
     * Reads the eligible incremental pull boundary without changing any stored data.
     * Serialized through the existing handling Mutex; a new read attempt supersedes
     * the previous checkpoint token, including a failed read. In one Room
     * transaction this checks session/epoch fences and reads the selected-account
     * cache and sync state coherently. A present row with an empty nonnull cursor
     * or a negative nonnull last-success is corrupt and fails closed without repair;
     * opaque whitespace tokens are preserved exactly. Null means bootstrap/baseline
     * work is needed: absent/incomplete cache, absent state row or null cursor.
     * Otherwise returns a fresh token binding this run and the complete observed
     * row, after rechecking fences, eligibility/admission and cancellation before
     * transaction completion and before return.
     */
    suspend fun readPullCheckpoint(): PullCheckpoint? {
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Pull checkpoint requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            // Fresh checkpoint objects are never reused, so clearing the current
            // token retires every prior token forever, including on a failed read.
            currentPullCheckpoint = null
            try {
                val observed = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    currentCoroutineContext().ensureActive()
                    val cache = database.syncCanonicalDao().getAccountCache(accountId)
                    val state = database.syncDao().getSyncState(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (state != null) {
                        validatePullSyncState(state)
                    }
                    if (cache == null || !cache.baselineComplete || state == null || state.cursor == null) {
                        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                        currentCoroutineContext().ensureActive()
                        requireAdmitted()
                        null
                    } else {
                        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                        currentCoroutineContext().ensureActive()
                        requireAdmitted()
                        state
                    }
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                if (observed == null) {
                    return@withLock null
                }
                val cursor = observed.cursor
                    ?: throw IllegalStateException("Pull checkpoint changed during transaction")
                val checkpoint = PullCheckpoint(
                    run = this,
                    accountId = accountId,
                    sessionGeneration = sessionGeneration,
                    expectedCacheEpoch = expectedCacheEpoch,
                    cursor = cursor,
                    lastSuccessMillis = observed.lastSuccessMillis,
                )
                currentPullCheckpoint = checkpoint
                checkpoint
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PullStorageException()
            }
        }
    }

    /**
     * Read-only fenced validation that the pull boundary still matches the
     * checkpoint's exact observed full sync-state row with a complete baseline.
     * Foreign token/run, closed/outside-run, enclosing-transaction, consumed or
     * superseded use is rejected; a changed/deleted checkpoint row or a lost
     * baseline is stale, while changed session/epoch keeps the existing fence
     * errors. Success is evidence only at that boundary: it cannot recall
     * already-admitted network bytes or authorize later writes without
     * transaction rechecks. Never consumes the token.
     */
    suspend fun checkPullCheckpoint(checkpoint: PullCheckpoint) {
        if (checkpoint.run !== this) {
            throw IllegalStateException("Pull checkpoint belongs to another run")
        }
        if (checkpoint.accountId != accountId ||
            checkpoint.sessionGeneration != sessionGeneration ||
            checkpoint.expectedCacheEpoch != expectedCacheEpoch
        ) {
            throw StalePullCheckpointException()
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Pull checkpoint requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        handlingMutex.withLock {
            if (currentPullCheckpoint !== checkpoint) {
                throw StalePullCheckpointException()
            }
            try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    val cache = database.syncCanonicalDao().getAccountCache(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (cache == null || !cache.baselineComplete) {
                        throw StalePullCheckpointException()
                    }
                    val state = database.syncDao().getSyncState(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    requireExactPullState(
                        SyncStateEntity(
                            accountId = accountId,
                            cursor = checkpoint.cursor,
                            lastSuccessMillis = checkpoint.lastSuccessMillis,
                        ),
                        state,
                    )
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PullStorageException()
            }
        }
    }

    /**
     * Atomically commits one complete owned canonical batch together with its next
     * pull cursor. The supplied whole batch is deep-owned and fully validated
     * before the first suspension, so caller mutation after suspension cannot
     * change it; a batch cursor equal to the input checkpoint cursor is rejected
     * as no progress with exact token equality only. Under the handling Mutex in
     * exactly one Room transaction this rechecks fences, complete baseline, the
     * exact observed full row, token admission and cancellation, then conservatively
     * consumes the token immediately before the canonical/cursor write attempt:
     * consumption is process-local, survives rollback or uncertain commit, and is
     * never persistence evidence. The complete owned batch is applied through the
     * existing canonical writer (all-obsolete/equal batches keep their no-op
     * semantics and still advance the cursor), then the cursor-only update must
     * affect exactly one row. All canonical changes, dirty marking and cursor
     * advancement share the same transaction; the full expected post-write row
     * (new cursor, old last-success unchanged), baseline, fences, live capability
     * and cancellation are rechecked before commit. Returns the canonical result
     * plus a fresh current token binding the new full state only after successful
     * commit and active resumption; the old token stays stale and a failure never
     * advertises a successful checkpoint. Earlier separately committed batches
     * remain durable when a later batch rolls back.
     */
    suspend fun applyNextPullBatch(
        checkpoint: PullCheckpoint,
        batch: SyncBatchModel,
    ): PullBatchCommitResult {
        val owned = ownedCanonicalBatch(batch)
        if (owned.cursor == checkpoint.cursor) {
            throw SyncModelException("Pull batch cursor did not advance")
        }
        if (checkpoint.run !== this) {
            throw IllegalStateException("Pull checkpoint belongs to another run")
        }
        if (checkpoint.accountId != accountId ||
            checkpoint.sessionGeneration != sessionGeneration ||
            checkpoint.expectedCacheEpoch != expectedCacheEpoch
        ) {
            throw StalePullCheckpointException()
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Pull checkpoint requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            if (currentPullCheckpoint !== checkpoint) {
                throw StalePullCheckpointException()
            }
            try {
                val canonical = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    val cache = database.syncCanonicalDao().getAccountCache(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (cache == null || !cache.baselineComplete) {
                        throw StalePullCheckpointException()
                    }
                    val observed = SyncStateEntity(
                        accountId = accountId,
                        cursor = checkpoint.cursor,
                        lastSuccessMillis = checkpoint.lastSuccessMillis,
                    )
                    requireExactPullState(observed, database.syncDao().getSyncState(accountId))
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    // Fail-closed retirement: clear the current token immediately
                    // before any canonical/cursor write attempt and never restore it,
                    // so rollback or uncertain commit still requires a fresh read of
                    // the durable boundary. Read-only checks never clear the token.
                    currentPullCheckpoint = null
                    val result = applyOwnedCanonicalBatch(
                        database,
                        accountId,
                        sessionGeneration,
                        expectedCacheEpoch,
                        owned,
                    )
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val advanced = database.syncDao().advancePullCursor(
                        accountId,
                        checkpoint.cursor,
                        owned.cursor,
                    )
                    if (advanced != 1) {
                        throw StalePullCheckpointException()
                    }
                    requireAdvancedPullState(
                        observed,
                        owned.cursor,
                        database.syncDao().getSyncState(accountId),
                    )
                    val postCache = database.syncCanonicalDao().getAccountCache(accountId)
                    if (postCache == null || !postCache.baselineComplete) {
                        throw StalePullCheckpointException()
                    }
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    result
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                val fresh = PullCheckpoint(
                    run = this,
                    accountId = accountId,
                    sessionGeneration = sessionGeneration,
                    expectedCacheEpoch = expectedCacheEpoch,
                    cursor = owned.cursor,
                    lastSuccessMillis = checkpoint.lastSuccessMillis,
                )
                currentPullCheckpoint = fresh
                PullBatchCommitResult(canonical = canonical, checkpoint = fresh)
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PullStorageException()
            }
        }
    }

    /**
     * Atomically records one validated terminal pull boundary as the account's last
     * success time. The caller proves validated terminal transport exhaustion; this is a
     * transaction primitive and generic checkpoints alone are not tail evidence.
     *
     * The supplied wall-clock milliseconds are validated before any write and preserved
     * exactly: no inferred ordering, clamping or max with prior time. Time is observation
     * metadata, never a conflict or cursor clock.
     *
     * Under the shared handling Mutex in exactly one Room transaction this rechecks live
     * admission/cancellation, canonical fences, the complete selected-account baseline and
     * the exact observed full sync-state row (cursor plus last-success, including nulls).
     * A missing/changed row or baseline is stale; session/epoch/storage errors keep their
     * existing types and fixed safe messages. Selected-account outbox presence is sampled
     * before retirement and rechecked before commit without parsing, filtering, claiming
     * or updating any row; foreign-account rows never count and this observation is not
     * an admission to send. A presence change across the write fails closed as stale.
     *
     * Immediately before the last-success write the input token retires, even for equal
     * timestamps, and is never restored on rollback or uncertain commit. The narrow DAO
     * update touches only last-success with exact account/cursor/previous-last-success
     * predicates; it never upserts, inserts, replaces, advances the cursor or mutates
     * canonical/cache/domain/outbox/watermark/device/catalogue/other-account state. The
     * update must affect exactly one row; the reread row must keep the account/cursor with
     * last-success exactly the supplied time. Baseline, session, epoch, live capability,
     * cancellation and the outbox observation are rechecked before commit so trigger
     * tampering fails closed with uncommitted effects rolled back. No uncertain return
     * claims recorded success.
     *
     * Only after the returned commit and active resumption is a fresh checkpoint binding
     * the new full row installed and returned with the observed presence flag. Old tokens
     * stay stale; failed or uncertain commits require a durable reread while previously
     * acknowledged heads and complete batch boundaries survive.
     */
    suspend fun recordPullTail(
        checkpoint: PullCheckpoint,
        nowMillis: Long,
    ): PullTailCommitResult {
        if (nowMillis < 0) {
            throw IllegalArgumentException("Pull tail time must be nonnegative")
        }
        if (checkpoint.run !== this) {
            throw IllegalStateException("Pull checkpoint belongs to another run")
        }
        if (checkpoint.accountId != accountId ||
            checkpoint.sessionGeneration != sessionGeneration ||
            checkpoint.expectedCacheEpoch != expectedCacheEpoch
        ) {
            throw StalePullCheckpointException()
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Pull checkpoint requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            if (currentPullCheckpoint !== checkpoint) {
                throw StalePullCheckpointException()
            }
            try {
                val observedPresence = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    val cache = database.syncCanonicalDao().getAccountCache(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (cache == null || !cache.baselineComplete) {
                        throw StalePullCheckpointException()
                    }
                    val observed = SyncStateEntity(
                        accountId = accountId,
                        cursor = checkpoint.cursor,
                        lastSuccessMillis = checkpoint.lastSuccessMillis,
                    )
                    val current = database.syncDao().getSyncState(accountId)
                    requireExactPullState(observed, current)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val presenceBefore = database.syncDao().hasOutboxForAccount(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    // Fail-closed retirement: clear the current token immediately
                    // before the last-success write attempt and never restore it, so
                    // rollback or uncertain commit still requires a durable reread.
                    // Read-only checks never clear the token.
                    currentPullCheckpoint = null
                    val updated = database.syncDao().updatePullLastSuccess(
                        accountId,
                        checkpoint.cursor,
                        checkpoint.lastSuccessMillis,
                        nowMillis,
                    )
                    if (updated != 1) {
                        throw StalePullCheckpointException()
                    }
                    val reread = database.syncDao().getSyncState(accountId)
                    if (reread == null ||
                        reread.accountId != accountId ||
                        reread.cursor != checkpoint.cursor ||
                        reread.lastSuccessMillis != nowMillis
                    ) {
                        throw StalePullCheckpointException()
                    }
                    val presenceAfter = database.syncDao().hasOutboxForAccount(accountId)
                    if (presenceAfter != presenceBefore) {
                        throw StalePullCheckpointException()
                    }
                    val postCache = database.syncCanonicalDao().getAccountCache(accountId)
                    if (postCache == null || !postCache.baselineComplete) {
                        throw StalePullCheckpointException()
                    }
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    presenceAfter
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                val fresh = PullCheckpoint(
                    run = this,
                    accountId = accountId,
                    sessionGeneration = sessionGeneration,
                    expectedCacheEpoch = expectedCacheEpoch,
                    cursor = checkpoint.cursor,
                    lastSuccessMillis = nowMillis,
                )
                currentPullCheckpoint = fresh
                PullTailCommitResult(checkpoint = fresh, hasOutstandingLocalWork = observedPresence)
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PullStorageException()
            }
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

/** Pure classification of one deep-owned push response against its claim. */
private sealed class PushResultDecision {
    class Accepted(val outcome: MutationOutcomeModel) : PushResultDecision()
    class Rejected(val errorCode: String) : PushResultDecision()
    data object Malformed : PushResultDecision()
}

/**
 * Pure single-head classifier. Accepts exactly one outcome with the exact claimed
 * mutation ID; accepted/duplicate-accepted batches additionally require the
 * envelope target with DELETE for aggregate.delete and UPSERT otherwise.
 * Anything else — including failed ownership/validation, empty/extra/mismatched
 * outcomes and invalid batch/record/status combinations — is malformed.
 */
private fun classifyPushResult(claim: PushClaim, owned: PushResultModel?): PushResultDecision {
    if (owned == null || owned.results.size != 1) {
        return PushResultDecision.Malformed
    }
    val outcome = owned.results[0]
    if (outcome.mutationId != claim.row.mutationId) {
        return PushResultDecision.Malformed
    }
    return when (outcome.status) {
        MutationStatus.ACCEPTED -> {
            if (!pushAcceptedTargetValid(claim, outcome)) {
                PushResultDecision.Malformed
            } else {
                PushResultDecision.Accepted(outcome)
            }
        }
        MutationStatus.DUPLICATE -> {
            val batch = outcome.batch
            val code = outcome.errorCode
            if (batch != null && code == null) {
                if (!pushAcceptedTargetValid(claim, outcome)) {
                    PushResultDecision.Malformed
                } else {
                    PushResultDecision.Accepted(outcome)
                }
            } else if (batch == null && !code.isNullOrBlank()) {
                PushResultDecision.Rejected(code)
            } else {
                PushResultDecision.Malformed
            }
        }
        MutationStatus.REJECTED -> {
            val code = outcome.errorCode
            if (code.isNullOrBlank()) {
                PushResultDecision.Malformed
            } else {
                PushResultDecision.Rejected(code)
            }
        }
    }
}

/**
 * Pure envelope-target check for accepted batches. The batch must contain the
 * claimed envelope target; operation is DELETE for aggregate.delete, otherwise
 * UPSERT. Never compares device clocks/cursors and never rejects an old
 * positive-revision duplicate for possibly being obsolete.
 */
private fun pushAcceptedTargetValid(claim: PushClaim, outcome: MutationOutcomeModel): Boolean {
    val batch = outcome.batch ?: return false
    if (outcome.errorCode != null) {
        return false
    }
    val envelope = decodeDurableRequest(parseStrictJsonObject(claim.row.payloadJson)).envelope
    val target = batch.changes.firstOrNull {
        it.aggregateType == envelope.aggregateType && it.aggregateId == envelope.aggregateId
    } ?: return false
    val expected = if (envelope.kind == MutationKind.AGGREGATE_DELETE) {
        ChangeOperation.DELETE
    } else {
        ChangeOperation.UPSERT
    }
    return target.operation == expected
}
