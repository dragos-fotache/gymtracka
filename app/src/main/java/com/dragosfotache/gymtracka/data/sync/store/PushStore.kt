package com.dragosfotache.gymtracka.data.sync.store

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotRecordEntity
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotStageEntity
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotTokenEntity
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.DEFAULT_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MAX_SNAPSHOT_LIMIT
import com.dragosfotache.gymtracka.data.sync.api.MIN_SNAPSHOT_LIMIT
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
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportException
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement
import com.dragosfotache.gymtracka.data.sync.session.LegacyEvidenceSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityException
import com.dragosfotache.gymtracka.data.sync.session.PreparedSessionEnginePermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/**
 * Shared process-local owner for one physical database's sync runs. Serializes explicit-head
 * claiming, transport outside Room, canonical acknowledgment, pull, snapshots and publication.
 * Each store transaction separately rechecks session, cache epoch and exact retained evidence.
 * This owner is not a multiprocess lease or durable account authority.
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
    private val processCheck: (() -> Unit)? = null,
) {
    private var preparedPermit: PreparedSessionEnginePermit? = null

    init {
        if (session.accountId == null) {
            throw IllegalArgumentException("Push store requires a signed-in account")
        }
        if (session.sessionGeneration <= 0) {
            throw IllegalArgumentException("Push store requires a positive session generation")
        }
    }

    /**
     * Explicitly constructed prepared store path. Binds one exact private
     * permit to this store; ordinary [withRun] admission stays ACTIVE-only
     * and never uses the permit. Prepared work enters only through
     * [withPreparedRun] with this same permit instance.
     */
    internal constructor(
        database: GymDatabase,
        session: RepositorySession,
        owner: PushRunOwner,
        permit: PreparedSessionEnginePermit,
        processCheck: (() -> Unit)? = null,
    ) : this(database, session, owner, processCheck) {
        preparedPermit = permit
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
            processCheck?.invoke()
            val run = PushRun(database, accountId, session.sessionGeneration, expectedCacheEpoch, null, processCheck)
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
                                null,
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

    /**
     * Prepared admission entry sharing the [withRun] lifecycle on the same
     * shared owner mutex: nested/enclosing-transaction entry rejects,
     * cancellation while waiting performs no Room work, and the capability
     * closes with ownership release in finally after structured children
     * settle. The strict durable epoch is read through [readEpoch] AFTER
     * acquiring the owner mutex and BEFORE run admission; admission then
     * rechecks private authority and that same epoch inside Room. The
     * supplied [permit] must be this store's bound prepared permit
     * instance. Ordinary ACTIVE admission never enters here.
     */
    internal suspend fun <T> withPreparedRun(
        permit: PreparedSessionEnginePermit,
        readEpoch: suspend () -> Long,
        block: suspend (PushRun) -> T,
    ): T {
        val bound = preparedPermit
        if (bound == null || bound !== permit) {
            throw IllegalStateException("Prepared run requires its prepared store and permit")
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
        if (permit.database !== database ||
            permit.accountId != accountId ||
            permit.sessionGeneration != session.sessionGeneration
        ) {
            throw IllegalStateException("Prepared permit does not match this store")
        }
        return owner.mutex.withLock {
            currentCoroutineContext().ensureActive()
            processCheck?.invoke()
            val expectedCacheEpoch = readEpoch()
            if (expectedCacheEpoch < 0) {
                throw IllegalStateException("Prepared epoch must be nonnegative")
            }
            currentCoroutineContext().ensureActive()
            val run = PushRun(database, accountId, session.sessionGeneration, expectedCacheEpoch, permit, processCheck)
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
                                permit,
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
    private var expectedCacheEpoch: Long,
    private val preparedPermit: PreparedSessionEnginePermit? = null,
    private val processCheck: (() -> Unit)? = null,
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

    /**
     * The run's one current snapshot-stage token, or null when no stage is
     * admitted. Guarded by [handlingMutex]. Tokens are fresh objects that are
     * never reused, so clearing this field retires every prior token forever:
     * a new read or begin attempt clears it (even on a failed attempt) and a
     * page commit or abandonment clears it immediately before any write.
     * Read-only checks never clear. Under the handling Mutex, referential
     * comparison against this field alone decides whether a token is current.
     */
    private var currentSnapshotStage: SnapshotStage? = null

    private var connectionRestored = false

    /** One connection-restoration event applies only to the first head selected by this run. */
    internal suspend fun retryConnectionHeadAfterReconnect() {
        requireAdmitted()
        connectionRestored = true
    }

    internal fun close() {
        open = false
    }

    /**
     * Current admitted cache epoch for progress reporting. Mutated only by
     * committed baseline/publication resumption under the handling mutex;
     * the coordinator reads it after commit and active resumption.
     */
    internal fun observedCacheEpoch(): Long = expectedCacheEpoch

    private suspend fun requireAdmitted() {
        if (!open || currentCoroutineContext()[PushRunScope.Key]?.run !== this) {
            throw IllegalStateException("Push run is not admitted")
        }
        currentCoroutineContext().ensureActive()
        processCheck?.invoke()
    }

    private fun requireStageToken(stage: SnapshotStage) {
        if (stage.run !== this) {
            throw IllegalStateException("Snapshot stage belongs to another run")
        }
        if (stage.accountId != accountId ||
            stage.sessionGeneration != sessionGeneration ||
            stage.expectedCacheEpoch != expectedCacheEpoch
        ) {
            throw StaleSnapshotStageException()
        }
    }

    /**
     * Selected-account durable stage presence without parsing any header,
     * record or token bytes. Foreign-account stages never match. Must run
     * inside the caller's active fenced transaction.
     */
    private suspend fun hasSelectedSnapshotStage(): Boolean {
        if (!database.inTransaction()) {
            throw IllegalStateException("Snapshot stage check requires an active transaction")
        }
        return database.syncSnapshotDao().getStageForAccount(accountId) != null
    }

    /**
     * Rejects a durable selected-account stage inside the caller's active
     * fenced transaction. Ordinary push/pull work stays excluded until the
     * stage is explicitly completed or abandoned.
     */
    private suspend fun requireNoSnapshotStage() {
        if (hasSelectedSnapshotStage()) {
            throw SnapshotStageActiveException()
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
        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
        val restored = connectionRestored
        connectionRestored = false
        currentCoroutineContext().ensureActive()
        if (hasSelectedSnapshotStage()) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.SNAPSHOT_STAGE)
        }
        currentCoroutineContext().ensureActive()
        val dao = database.syncDao()
        val head = dao.getOutboxHead(accountId) ?: run {
            currentCoroutineContext().ensureActive()
            try {
                com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportGate.checkEmpty(
                    database, accountId,
                )?.let { return PushHeadResult.Blocked(it) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
            }
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Empty
        }
        currentCoroutineContext().ensureActive()
        if (head.sequence <= 0L ||
            head.attemptCount < 0 ||
            (head.retryAfterMillis != null && head.retryAfterMillis < 0L) ||
            head.retryCause !in setOf("unknown", "connection") ||
            (head.retryCause == "connection" && (head.state != "frozen" || head.retryAfterMillis == null || head.attemptCount < 1))
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
        checkLegacyImportGate(head)?.let { return it }
        val retryAt = head.retryAfterMillis
        val resetConnectionDelay = restored && head.retryCause == "connection"
        if (retryAt != null && retryAt > nowMillis && !resetConnectionDelay) {
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
            resetConnectionDelay,
        )
        if (updated != 1) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val frozen = head.copy(
            state = "frozen",
            attemptCount = newAttemptCount,
            retryAfterMillis = if (resetConnectionDelay) null else head.retryAfterMillis,
            retryCause = if (resetConnectionDelay) "unknown" else head.retryCause,
        )
        if (dao.getOutboxHead(accountId) != frozen) {
            throw IllegalStateException("Push head changed during transaction")
        }
        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
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
     * legacy-import send gate. No legacy journal means ordinary
     * behavior. With a journal, strictly validates settlement state
     * (progress anchor, consecutive accepted prefix, outbox correspondence,
     * frozen source/entries) before any HTTP/freeze effect beyond the
     * already-read head. Corrupt evidence blocks CORRUPT; an original
     * active import without durable matching consent blocks RETAINED_ERROR
     * with zero remote effects. Reads only.
     */
    private suspend fun checkLegacyImportGate(head: com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity): PushHeadResult.Blocked? {
        currentCoroutineContext().ensureActive()
        val legacyGate = try {
            com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportGate.checkHead(
                database, accountId, head,
            )
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return PushHeadResult.Blocked(PushBlockReason.CORRUPT)
        }
        currentCoroutineContext().ensureActive()
        return legacyGate?.let { PushHeadResult.Blocked(it) }
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                                    retryCause = if (failure.connectionFailure && failure.retryAfterMillis == null) "connection" else "unknown",
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
     * claimed head: applies the whole batch with canonical batches arbitration/family
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                            val legacyReceipt = com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportAck.precheck(
                                database, accountId, claim.row, ownedBatch,
                            )
                            val canonical = applyOwnedCanonicalBatch(
                                database,
                                accountId,
                                sessionGeneration,
                                expectedCacheEpoch,
                                ownedBatch,
                                preparedPermit,
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
                            if (legacyReceipt != null) {
                                com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportAck.persistReceipt(
                                    database, legacyReceipt,
                                )
                            }
                            database.syncCanonicalDao().insertAccountCache(accountId)
                            if (database.syncCanonicalDao().markProjectionDirty(accountId) != 1) {
                                throw IllegalStateException("Account cache changed during transaction")
                            }
                            recheckAckMetadata(database, expectations)
                            recheckAckWatermarks(database, accountId, context.roots)
                            if (legacyReceipt != null) {
                                com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportAck.recheck(
                                    database, accountId, legacyReceipt,
                                )
                            }
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
                                preparedPermit,
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
        retryCause: String = "unknown",
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
            retryCause = retryCause,
        )
        if (updated != 1) {
            throw IllegalStateException("Push head changed during transaction")
        }
        val expected = claim.row.copy(
            state = newState,
            errorCode = errorCode,
            errorMessage = errorMessage,
            retryAfterMillis = retryAfterMillis,
            retryCause = retryCause,
        )
        val headAfter = database.syncDao().getOutboxHead(accountId)
        currentCoroutineContext().ensureActive()
        requireAdmitted()
        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
        currentCoroutineContext().ensureActive()
        if (headAfter != expected) {
            throw IllegalStateException("Push head changed during transaction")
        }
    }

    /**
     * confirmed ordinary discard. In one fenced Room transaction deletes only the
     * caller-captured exact ordinary rows, resets the selected pull cursor to null while
     * preserving recorded last-success, and marks the projection dirty. The caller compared
     * the complete captured rows/authority/epoch/legacy proof immediately before this call;
     * this method revalidates full-row equality, the exact ready Room authority and the
     * strict legacy/source/receipt snapshot inside the transaction through the final SQL,
     * then rechecks the supplied drain lease, process, session, epoch and caller fences AFTER
     * the final SQL before commit. Any fault rolls everything back. Domain graph, canonical
     * roots/watermarks, credentials, foreign scope, catalogue and import evidence are untouched.
     * Must run inside the caller's admitted run; performs no HTTP and claims no successor.
     */
    internal suspend fun discardExactOrdinaryRows(
        captured: List<com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity>,
        expectedRoom: RecoveryRoomSnapshot,
        expectedLegacy: LegacyEvidenceSnapshot,
        leaseCheck: () -> Unit,
    ) {
        require(captured.isNotEmpty()) { "Ordinary discard requires captured rows" }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Ordinary discard requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val current = database.syncDao().getOutboxForAccount(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (current != captured) {
                        throw StalePushClaimException()
                    }
                    if (current.any { it.state != "pending" && it.state != "rejected" }) {
                        throw StalePushClaimException()
                    }
                    // Strict legacy settlement gate inside the transaction: valid-looking
                    // tamper that passes row equality but breaks import settlement proof
                    // refuses here, before any destructive effect.
                    try {
                        if (com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportGate.checkEmpty(
                                database,
                                accountId,
                            ) != null
                        ) {
                            throw StalePushClaimException()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    }
                    val stateBefore = database.syncDao().getSyncState(accountId)
                    val lastSuccessBefore = stateBefore?.lastSuccessMillis
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    for (row in captured) {
                        val deleted = database.syncDao().deleteCapturedOrdinaryRow(
                            mutationId = row.mutationId,
                            accountId = accountId,
                            sequence = row.sequence,
                            expectedState = row.state,
                            expectedAttempts = row.attemptCount,
                        )
                        if (deleted != 1) {
                            throw IllegalStateException("Ordinary row changed during transaction")
                        }
                    }
                    if (stateBefore == null) {
                        database.syncDao().upsertSyncState(SyncStateEntity(accountId, null, null))
                    } else {
                        val nulled = database.syncDao().nullCursorPreservingLastSuccess(accountId)
                        if (nulled != 1) {
                            throw IllegalStateException("Sync cursor changed during transaction")
                        }
                    }
                    database.syncCanonicalDao().insertAccountCache(accountId)
                    if (database.syncCanonicalDao().markProjectionDirty(accountId) != 1) {
                        throw IllegalStateException("Account cache changed during transaction")
                    }
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val remaining = database.syncDao().getOutboxForAccount(accountId)
                    if (remaining.any { it.mutationId in captured.map { row -> row.mutationId }.toSet() }) {
                        throw IllegalStateException("Ordinary row changed during transaction")
                    }
                    val stateAfter = database.syncDao().getSyncState(accountId)
                        ?: throw IllegalStateException("Sync cursor changed during transaction")
                    if (stateAfter.cursor != null || stateAfter.lastSuccessMillis != lastSuccessBefore) {
                        throw IllegalStateException("Sync cursor changed during transaction")
                    }
                    // In-transaction final authority/legacy guard through the last SQL: any
                    // trigger drift in ready Room lineage or strict import source/receipt
                    // evidence refuses stale with rollback. Corrupt reads refuse the same way;
                    // caller cancellation still dominates and propagates unwrapped.
                    val roomNow = try {
                        RoomSessionAuthority(database).read()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: RoomSessionAuthorityException) {
                        throw StalePushClaimException()
                    } catch (_: Exception) {
                        throw StalePushClaimException()
                    }
                    if (roomNow != expectedRoom) {
                        throw StalePushClaimException()
                    }
                    val legacyNow = try {
                        LegacyEvidenceSnapshot(
                            LegacyImportSettlement.readJournalStrict(database),
                            LegacyImportSettlement.readEntriesStrict(database),
                            LegacyImportSettlement.readProgressStrict(database),
                            LegacyImportSettlement.readAcceptancesStrict(database),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: LegacyImportException) {
                        throw StalePushClaimException()
                    } catch (_: Exception) {
                        throw StalePushClaimException()
                    }
                    if (legacyNow != expectedLegacy) {
                        throw StalePushClaimException()
                    }
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    try {
                        leaseCheck()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        throw e
                    } catch (_: Exception) {
                        throw IllegalStateException("Ordinary discard lease is no longer valid")
                    }
                    currentCoroutineContext().ensureActive()
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw PushStorageException()
            }
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
                    currentCoroutineContext().ensureActive()
                    val cache = database.syncCanonicalDao().getAccountCache(accountId)
                    val state = database.syncDao().getSyncState(accountId)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (state != null) {
                        validatePullSyncState(state)
                    }
                    if (cache == null || !cache.baselineComplete || state == null || state.cursor == null) {
                        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                        currentCoroutineContext().ensureActive()
                        requireAdmitted()
                        null
                    } else {
                        checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                        preparedPermit,
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    requireNoSnapshotStage()
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
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
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

    /**
     * Begins one durable frozen-snapshot stage for the selected account with an
     * explicit stage ID and fixed page limit. Caller UUID/limit mistakes fail
     * before storage with the existing argument/model validation. In exactly one
     * Room transaction this requires no retained selected stage, an empty
     * selected-account outbox in any state, and the exact origin source
     * observation, retires any previously admitted pull checkpoint, inserts only
     * the initial header, then rechecks source, baseline, outbox, header and
     * fences before commit. A retained stage, any outbox row, a corrupt source
     * row or a foreign stage-ID collision fails closed without overwriting
     * anything. Returns the appendable token only after commit and resumption.
     */
    suspend fun beginSnapshotStage(
        stageId: String,
        pageLimit: Int = DEFAULT_SNAPSHOT_LIMIT,
    ): SnapshotStage {
        requireWorkoutUuid(stageId, "stageId")
        if (pageLimit < MIN_SNAPSHOT_LIMIT || pageLimit > MAX_SNAPSHOT_LIMIT) {
            throw IllegalArgumentException("Snapshot page limit must be within 1..500")
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Snapshot staging requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            // A begin attempt supersedes the previous stage token, even on failure.
            currentSnapshotStage = null
            try {
                val validated = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    if (database.syncSnapshotDao().getStageForAccount(accountId) != null) {
                        throw SnapshotStageActiveException()
                    }
                    currentCoroutineContext().ensureActive()
                    if (database.syncDao().hasOutboxForAccount(accountId)) {
                        throw SnapshotStageIntentException()
                    }
                    currentCoroutineContext().ensureActive()
                    val legacyPinned = try {
                        com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.pinSettled(database)
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw SnapshotStageStorageException()
                    }
                    currentCoroutineContext().ensureActive()
                    val sourceState = database.syncDao().getSyncState(accountId)
                    if (sourceState != null) {
                        validateSnapshotSourceState(sourceState)
                    }
                    val sourceBaselineComplete =
                        database.syncCanonicalDao().getAccountCache(accountId)?.baselineComplete
                            ?: false
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    // Retire any previously admitted pull checkpoint before the
                    // first staging write; read-only checks never retire.
                    currentPullCheckpoint = null
                    val initial = SyncSnapshotStageEntity(
                        stageId = stageId,
                        accountId = accountId,
                        sessionGeneration = sessionGeneration,
                        cacheEpoch = expectedCacheEpoch,
                        pageLimit = pageLimit,
                        sourceStatePresent = sourceState != null,
                        sourceCursor = sourceState?.cursor,
                        sourceLastSuccessMillis = sourceState?.lastSuccessMillis,
                        sourceBaselineComplete = sourceBaselineComplete,
                        baselineCursor = null,
                        nextPageToken = null,
                        phase = SNAPSHOT_STAGE_DOWNLOADING,
                        pageCount = 0L,
                        recordCount = 0L,
                    )
                    database.syncSnapshotDao().insertStage(initial)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (database.syncSnapshotDao().getStage(stageId) != initial) {
                        throw StaleSnapshotStageException()
                    }
                    val reread = readValidatedSnapshotStage(database, accountId)
                        ?: throw StaleSnapshotStageException()
                    if (reread.header != initial) {
                        throw StaleSnapshotStageException()
                    }
                    if (database.syncDao().hasOutboxForAccount(accountId)) {
                        throw SnapshotStageIntentException()
                    }
                    if (database.syncDao().getSyncState(accountId) != sourceState) {
                        throw StaleSnapshotStageException()
                    }
                    val rereadBaseline =
                        database.syncCanonicalDao().getAccountCache(accountId)?.baselineComplete
                            ?: false
                    if (rereadBaseline != sourceBaselineComplete) {
                        throw StaleSnapshotStageException()
                    }
                    try {
                        com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.verifyPinnedUnchanged(
                            database,
                            legacyPinned,
                        )
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw SnapshotStageStorageException()
                    }
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    reread
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                val token = validated
                    .toSnapshotToken(
                        this,
                        accountId,
                        sessionGeneration,
                        expectedCacheEpoch,
                        canAppend = true,
                    )
                currentSnapshotStage = token
                token
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw SnapshotStageStorageException()
            }
        }
    }

    /**
     * Coherently reads the selected-account stage with its complete retained
     * evidence without changing any stored data. Every durable read supersedes
     * the prior stage token, even on null or failure. Returns an abandonable
     * observation that can never append, or null when no stage is retained.
     * Origin and source fences may differ from current admission: that means
     * recovery observation, never append authority. Corrupt retained evidence
     * fails closed as a storage error and is preserved.
     */
    suspend fun readSnapshotStage(): SnapshotStage? {
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Snapshot staging requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            currentSnapshotStage = null
            try {
                val validated = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val result = readValidatedSnapshotStage(database, accountId)
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    result
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                if (validated == null) {
                    return@withLock null
                }
                val token = validated.toSnapshotToken(
                    this,
                    accountId,
                    sessionGeneration,
                    expectedCacheEpoch,
                    canAppend = false,
                )
                currentSnapshotStage = token
                token
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw SnapshotStageStorageException()
            }
        }
    }

    /**
     * Read-only fenced validation that the retained stage still matches the
     * token's exact observed header with coherent valid evidence. Foreign,
     * superseded or recovery tokens used for appends are stale; an appendable
     * token additionally requires the origin fence and the complete source
     * binding still exact. Recovery observations skip old source fences solely
     * to permit explicit same-account abandonment. Never consumes the token.
     */
    suspend fun checkSnapshotStage(stage: SnapshotStage) {
        requireStageToken(stage)
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Snapshot staging requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        handlingMutex.withLock {
            if (currentSnapshotStage !== stage) {
                throw StaleSnapshotStageException()
            }
            try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    val validated = readValidatedSnapshotStage(database, accountId)
                        ?: throw StaleSnapshotStageException()
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (validated.header != stage.header) {
                        throw StaleSnapshotStageException()
                    }
                    if (stage.canAppend) {
                        if (validated.header.sessionGeneration != sessionGeneration ||
                            validated.header.cacheEpoch != expectedCacheEpoch
                        ) {
                            throw StaleSnapshotStageException()
                        }
                        requireExactSnapshotSource(database, accountId, validated.header)
                    }
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw SnapshotStageStorageException()
            }
        }
    }

    /**
     * Atomically stages one complete owned snapshot page: the entire typed page
     * is deep-owned and validated before the first suspension, so caller mutation
     * after suspension cannot change it. In exactly one Room transaction this
     * rechecks the appendable current token, downloading phase, exact retained
     * header, origin fence and unchanged source binding, binds the frozen
     * baseline on the first page and matches it exactly later, rejects duplicate
     * cross-page roots and repeated continuations before writes, then inserts
     * records with the new continuation and advances header and checked counters
     * atomically. The token retires immediately before the first write and is
     * never restored on rollback or uncertain commit; only the returned commit
     * installs the fresh token. Earlier committed pages survive later failure.
     * A terminal commit still establishes no baseline, absence, publication or
     * success. Local capture during download stays allowed: the outbox is never
     * rechecked here and staging never changes domain, outbox or watermarks.
     */
    suspend fun appendSnapshotPage(stage: SnapshotStage, page: SnapshotResultModel): SnapshotStage {
        val owned = ownedSnapshotPage(page)
        checkOwnedPageAgainstStage(owned, stage)
        requireStageToken(stage)
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Snapshot staging requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            if (currentSnapshotStage !== stage) {
                throw StaleSnapshotStageException()
            }
            if (!stage.canAppend || stage.downloaded) {
                throw StaleSnapshotStageException()
            }
            try {
                val committed = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    val validated = readValidatedSnapshotStage(database, accountId)
                        ?: throw StaleSnapshotStageException()
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (validated.header != stage.header) {
                        throw StaleSnapshotStageException()
                    }
                    if (validated.header.sessionGeneration != sessionGeneration ||
                        validated.header.cacheEpoch != expectedCacheEpoch
                    ) {
                        throw StaleSnapshotStageException()
                    }
                    requireExactSnapshotSource(database, accountId, validated.header)
                    if (validated.header.phase != SNAPSHOT_STAGE_DOWNLOADING) {
                        throw StaleSnapshotStageException()
                    }
                    val header = validated.header
                    val newBaseline = if (header.pageCount == 0L) {
                        owned.baselineCursor
                    } else {
                        if (owned.baselineCursor != header.baselineCursor) {
                            throw SyncModelException("Snapshot page changed the frozen baseline")
                        }
                        header.baselineCursor
                    }
                    val retainedKeys = validated.records
                        .map { it.aggregateType to it.aggregateId }
                        .toSet()
                    for (change in owned.records) {
                        if ((change.aggregateType.wireValue to change.aggregateId) in retainedKeys) {
                            throw SyncModelException("Snapshot page repeats a staged aggregate")
                        }
                    }
                    val retainedTokens = validated.tokens.map { it.pageToken }.toSet()
                    val newToken = if (owned.hasMore) {
                        val token = owned.nextPageToken
                            ?: throw SyncModelException("Snapshot page is missing its continuation")
                        if (token == header.nextPageToken || token in retainedTokens) {
                            throw SyncModelException("Snapshot page repeats a staged continuation")
                        }
                        token
                    } else {
                        null
                    }
                    if (header.pageCount == Long.MAX_VALUE) {
                        throw SnapshotStageStorageException()
                    }
                    val newPage = header.pageCount + 1L
                    if (header.recordCount > Long.MAX_VALUE - owned.records.size.toLong()) {
                        throw SnapshotStageStorageException()
                    }
                    val newRecordCount = header.recordCount + owned.records.size.toLong()
                    val newPhase = if (owned.hasMore) {
                        SNAPSHOT_STAGE_DOWNLOADING
                    } else {
                        SNAPSHOT_STAGE_DOWNLOADED
                    }
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    // Fail-closed retirement: clear the current token immediately
                    // before any record/token/header write attempt and never
                    // restore it, so rollback or uncertain commit still requires
                    // a durable reread. Read-only checks never clear the token.
                    currentSnapshotStage = null
                    val dao = database.syncSnapshotDao()
                    if (owned.records.isNotEmpty()) {
                        dao.insertRecords(
                            owned.records.mapIndexed { index, change ->
                                SyncSnapshotRecordEntity(
                                    stageId = header.stageId,
                                    aggregateType = change.aggregateType.wireValue,
                                    aggregateId = change.aggregateId,
                                    serverRevision = change.revision,
                                    canonicalJson = stagedRecordJson(change),
                                    pageNumber = newPage,
                                    recordPosition = index.toLong(),
                                )
                            },
                        )
                    }
                    if (newToken != null) {
                        dao.insertToken(
                            SyncSnapshotTokenEntity(
                                stageId = header.stageId,
                                pageToken = newToken,
                                pageNumber = newPage,
                            ),
                        )
                    }
                    val advanced = dao.advanceStage(
                        stageId = header.stageId,
                        accountId = accountId,
                        expectedPhase = header.phase,
                        expectedPageCount = header.pageCount,
                        expectedRecordCount = header.recordCount,
                        baselineCursor = newBaseline,
                        nextPageToken = newToken,
                        phase = newPhase,
                        pageCount = newPage,
                        recordCount = newRecordCount,
                    )
                    if (advanced != 1) {
                        throw StaleSnapshotStageException()
                    }
                    val expected = header.copy(
                        baselineCursor = newBaseline,
                        nextPageToken = newToken,
                        phase = newPhase,
                        pageCount = newPage,
                        recordCount = newRecordCount,
                    )
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val reread = readValidatedSnapshotStage(database, accountId)
                        ?: throw StaleSnapshotStageException()
                    if (reread.header != expected) {
                        throw StaleSnapshotStageException()
                    }
                    requireExactSnapshotSource(database, accountId, header)
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    reread
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                val token = committed.toSnapshotToken(
                    this,
                    accountId,
                    sessionGeneration,
                    expectedCacheEpoch,
                    canAppend = true,
                )
                currentSnapshotStage = token
                token
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw SnapshotStageStorageException()
            }
        }
    }

    /**
     * Explicitly abandons exactly the admitted stage with stage-only child
     * cascade. Recovery observations may abandon under the current fences
     * without matching the old origin or source observation, and abandonment
     * never requires an empty outbox nor changes domain, cache or checkpoint
     * state. The token retires immediately before the delete and is never
     * restored. Cancellation or run close without a committed delete retains
     * the stage; nothing cleans up automatically.
     */
    suspend fun abandonSnapshotStage(stage: SnapshotStage) {
        requireStageToken(stage)
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Snapshot staging requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        handlingMutex.withLock {
            if (currentSnapshotStage !== stage) {
                throw StaleSnapshotStageException()
            }
            try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    val validated = readValidatedSnapshotStage(database, accountId)
                        ?: throw StaleSnapshotStageException()
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (validated.header != stage.header) {
                        throw StaleSnapshotStageException()
                    }
                    // Fail-closed retirement before the delete attempt; never
                    // restored on rollback or uncertain commit.
                    currentSnapshotStage = null
                    val dao = database.syncSnapshotDao()
                    val deleted = dao.deleteStage(stage.stageId, accountId)
                    if (deleted != 1) {
                        throw StaleSnapshotStageException()
                    }
                    if (dao.getStage(stage.stageId) != null ||
                        dao.getRecords(stage.stageId).isNotEmpty() ||
                        dao.getTokens(stage.stageId).isNotEmpty()
                    ) {
                        throw StaleSnapshotStageException()
                    }
                    checkCanonicalFences(database, accountId, sessionGeneration, expectedCacheEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw SnapshotStageStorageException()
            }
        }
    }

    /**
     * Committed outcome of one complete baseline transaction, carried out of
     * Room before the live run epoch advances. Private to this file.
     */
    private data class BaselineCommit(
        val newEpoch: Long,
        val baseline: String,
        val pageCount: Long,
        val recordCount: Long,
        val hasOutstandingLocalWork: Boolean,
    )

    /**
     * Atomically establishes the complete canonical baseline for one actually
     * issued terminal downloaded stage on this SAME admitted run. The driver
     * proves terminal issuance and consumes it through [onEligible] at the
     * eligible boundary: after token identity admission under the handling
     * Mutex and before entering the Room transaction. The callback must
     * require successful removal, so concurrently prechecked attempts cannot
     * both pass; later graph/revision/overflow validation failures leave the
     * issuance retired, with recovery through durable reread and explicit
     * abandon/restart. Wrong/closed/outside/current-token rejection never
     * reaches consumption and cannot retire another valid result. This
     * operation proves the current exact stage referentially and performs the
     * fenced Room work. No HTTP, push/pull, retry, clock, cleanup-on-error or
     * automatic publication occurs here.
     *
     * Under the handling Mutex, in ONE fenced Room transaction: rechecks the
     * exact full stage header with all strict retained record/token/page
     * evidence (downloaded terminal phase, positive page count, null
     * continuation, exact nonempty baseline, origin generation/epoch and the
     * original full source sync-state/baseline observation); deep-decodes all
     * staged whole canonical upserts and validates the COMPLETE frozen graph;
     * validates family bindings against retained evidence; arbitrates
     * revisions fail-closed (lower fails, equal must agree authoritatively and
     * never restores delete/absence, higher replaces); retires the exact
     * stage/checkpoint capabilities before the first write; installs every
     * staged root as a complete upsert at the checked new epoch preserving
     * exact watermarks; marks previously observed missing roots
     * snapshot_absent retaining known revisions; establishes baselineComplete,
     * the new cache epoch and dirty while preserving the materialized epoch;
     * sets the cursor to the exact frozen baseline preserving source
     * last-success; deletes exactly the completed stage; and rechecks all
     * outcomes, counts, the new epoch, session, admission and cancellation
     * before commit. Any graph/conflict/fence/storage/cancellation failure
     * rolls everything back together. Only a committed resumption with active
     * resumption advances the live run epoch and returns the result. No
     * visible domain rows change. Newer captured outbox work stays preserved
     * and is only observed, never required empty, parsed or rewritten.
     */
    internal suspend fun completeSnapshotStage(
        stage: SnapshotStage,
        onEligible: () -> Unit,
    ): SnapshotBaselineResult {
        requireStageToken(stage)
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Snapshot baseline requires no active transaction")
        }
        if (!stage.downloaded || stage.canAppend) {
            throw StaleSnapshotStageException()
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            if (currentSnapshotStage !== stage) {
                throw StaleSnapshotStageException()
            }
            currentCoroutineContext().ensureActive()
            // Eligible boundary: consume driver terminal issuance here, after
            // admission and current-token identity under the handling Mutex
            // and before the Room transaction/first suspension. The callback
            // requires successful removal; later validation failures leave it
            // retired without authorizing another attempt.
            onEligible()
            try {
                val committed = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    val oldEpoch = checkCanonicalFences(
                        database,
                        accountId,
                        sessionGeneration,
                        expectedCacheEpoch,
                        preparedPermit,
                    )
                    val validated = readValidatedSnapshotStage(database, accountId)
                        ?: throw StaleSnapshotStageException()
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (validated.header != stage.header) {
                        throw StaleSnapshotStageException()
                    }
                    if (validated.header.phase != SNAPSHOT_STAGE_DOWNLOADED ||
                        validated.header.pageCount < 1L ||
                        validated.header.nextPageToken != null ||
                        validated.header.baselineCursor.isNullOrEmpty()
                    ) {
                        throw SnapshotStageStorageException()
                    }
                    if (validated.header.sessionGeneration != sessionGeneration ||
                        validated.header.cacheEpoch != expectedCacheEpoch
                    ) {
                        throw StaleSnapshotStageException()
                    }
                    requireExactSnapshotSource(database, accountId, validated.header)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val legacyPinned = try {
                        com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.pinSettled(database)
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw SnapshotStageStorageException()
                    }
                    val baseline = validated.header.baselineCursor
                    val staged = decodeStagedBaseline(validated.records)
                    val retained = loadValidatedRetained(database, accountId, oldEpoch)
                    checkFamilyBindings(retained, staged.map { it.change })
                    validateCompleteBaselineGraph(database, staged)
                    arbitrateBaselineRevisions(retained, staged)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    if (oldEpoch == Long.MAX_VALUE) {
                        throw SnapshotStageStorageException()
                    }
                    val newEpoch = oldEpoch + 1L
                    // Fail-closed capability retirement before the first write;
                    // never restored on rollback or uncertain commit. Driver
                    // terminal issuance already retired at the eligible boundary.
                    currentPullCheckpoint = null
                    currentSnapshotStage = null
                    val canonicalDao = database.syncCanonicalDao()
                    val syncDao = database.syncDao()
                    val snapshotDao = database.syncSnapshotDao()
                    val stagedByKey = staged.associateBy { it.key }
                    val expectedWatermarks = HashMap<Pair<String, String>, Long>()
                    for (item in staged) {
                        val existing = retained.byKey[item.key]
                        expectedWatermarks[item.key] =
                            existing?.localGenerationWatermark ?: 0L
                        if (existing == null) {
                            canonicalDao.insertCanonicalRoot(
                                accountId = accountId,
                                aggregateType = item.change.aggregateType.wireValue,
                                aggregateId = item.change.aggregateId,
                                serverRevision = item.change.revision,
                                canonicalState = "upsert",
                                canonicalJson = item.json,
                                watermark = 0L,
                                cacheEpoch = newEpoch,
                            )
                        } else {
                            val updated = canonicalDao.updateCanonicalBody(
                                accountId = accountId,
                                aggregateType = item.change.aggregateType.wireValue,
                                aggregateId = item.change.aggregateId,
                                serverRevision = item.change.revision,
                                canonicalState = "upsert",
                                canonicalJson = item.json,
                                cacheEpoch = newEpoch,
                            )
                            if (updated != 1) {
                                throw IllegalStateException(
                                    "Canonical root changed during transaction",
                                )
                            }
                        }
                        currentCoroutineContext().ensureActive()
                    }
                    requireAdmitted()
                    val absentKeys = retained.rows
                        .filter { it.canonicalState != "unobserved" }
                        .map { it.aggregateType to it.aggregateId }
                        .filter { it !in stagedByKey }
                    for (key in absentKeys) {
                        val existing = retained.byKey[key]
                            ?: throw CanonicalStorageException()
                        val updated = canonicalDao.updateCanonicalBody(
                            accountId = accountId,
                            aggregateType = key.first,
                            aggregateId = key.second,
                            serverRevision = existing.serverRevision,
                            canonicalState = "snapshot_absent",
                            canonicalJson = null,
                            cacheEpoch = newEpoch,
                        )
                        if (updated != 1) {
                            throw IllegalStateException(
                                "Canonical root changed during transaction",
                            )
                        }
                    }
                    canonicalDao.insertAccountCache(accountId)
                    val oldMaterialized =
                        canonicalDao.getAccountCache(accountId)?.materializedEpoch ?: 0L
                    val baselined = canonicalDao.establishBaseline(accountId, newEpoch)
                    if (baselined != 1) {
                        throw IllegalStateException("Account cache changed during transaction")
                    }
                    syncDao.upsertSyncState(
                        SyncStateEntity(
                            accountId = accountId,
                            cursor = baseline,
                            lastSuccessMillis = validated.header.sourceLastSuccessMillis,
                        ),
                    )
                    val hasWork = syncDao.hasOutboxForAccount(accountId)
                    val deleted = snapshotDao.deleteStage(stage.stageId, accountId)
                    if (deleted != 1) {
                        throw StaleSnapshotStageException()
                    }
                    if (snapshotDao.getStage(stage.stageId) != null ||
                        snapshotDao.getRecords(stage.stageId).isNotEmpty() ||
                        snapshotDao.getTokens(stage.stageId).isNotEmpty()
                    ) {
                        throw StaleSnapshotStageException()
                    }
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    val rereadRoots = canonicalDao.getRootsForAccount(accountId)
                        .associateBy { it.aggregateType to it.aggregateId }
                    for (item in staged) {
                        val row = rereadRoots[item.key]
                            ?: throw IllegalStateException(
                                "Canonical root changed during transaction",
                            )
                        if (row.canonicalState != "upsert" ||
                            row.serverRevision != item.change.revision ||
                            row.canonicalJson != item.json ||
                            row.cacheEpoch != newEpoch ||
                            row.localGenerationWatermark != expectedWatermarks[item.key]
                        ) {
                            throw IllegalStateException(
                                "Canonical root changed during transaction",
                            )
                        }
                    }
                    for (key in absentKeys) {
                        val row = rereadRoots[key]
                            ?: throw IllegalStateException(
                                "Canonical root changed during transaction",
                            )
                        val before = retained.byKey[key]
                            ?: throw CanonicalStorageException()
                        if (row.canonicalState != "snapshot_absent" ||
                            row.canonicalJson != null ||
                            row.serverRevision != before.serverRevision ||
                            row.cacheEpoch != newEpoch
                        ) {
                            throw IllegalStateException(
                                "Canonical root changed during transaction",
                            )
                        }
                    }
                    val cache = canonicalDao.getAccountCache(accountId)
                        ?: throw IllegalStateException("Account cache changed during transaction")
                    if (!cache.baselineComplete ||
                        cache.cacheEpoch != newEpoch ||
                        !cache.projectionDirty ||
                        cache.materializedEpoch != oldMaterialized
                    ) {
                        throw IllegalStateException("Account cache changed during transaction")
                    }
                    val state = syncDao.getSyncState(accountId)
                        ?: throw IllegalStateException("Sync checkpoint changed during transaction")
                    if (state.cursor != baseline ||
                        state.lastSuccessMillis != validated.header.sourceLastSuccessMillis
                    ) {
                        throw IllegalStateException("Sync checkpoint changed during transaction")
                    }
                    try {
                        com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportSettlement.verifyPinnedUnchanged(
                            database,
                            legacyPinned,
                        )
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw SnapshotStageStorageException()
                    }
                    checkCanonicalFences(database, accountId, sessionGeneration, newEpoch, preparedPermit)
                    currentCoroutineContext().ensureActive()
                    requireAdmitted()
                    BaselineCommit(
                        newEpoch = newEpoch,
                        baseline = baseline,
                        pageCount = validated.header.pageCount,
                        recordCount = validated.header.recordCount,
                        hasOutstandingLocalWork = hasWork,
                    )
                }
                currentCoroutineContext().ensureActive()
                requireAdmitted()
                expectedCacheEpoch = committed.newEpoch
                SnapshotBaselineResult(
                    cacheEpoch = committed.newEpoch,
                    baselineCursor = committed.baseline,
                    pageCount = committed.pageCount,
                    recordCount = committed.recordCount,
                    hasOutstandingLocalWork = committed.hasOutstandingLocalWork,
                )
            } catch (_: SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw SnapshotStageStorageException()
            }
        }
    }

    /**
     * Publishes the complete dirty baseline through the paired publisher on
     * this SAME admitted run. Admits and rechecks the run, requires no
     * enclosing Room transaction, verifies publisher pairing with the SAME
     * physical database/account/session before lease acquisition, and
     * serializes the entire publication invocation on the existing handling
     * Mutex. Before invoking the paired publisher it conservatively retires
     * the prior checkpoint/stage process-local capabilities without durable
     * cleanup; failures require durable reread rather than reusing an
     * unchanged old checkpoint token after an uncertain attempt. The existing
     * publisher runs outside an enclosing Room transaction, allowing its
     * required barrier pause/drain before its own transaction. No new owner,
     * barrier or permissive lease is introduced.
     * A returned publication advances the live run epoch to the returned
     * cache epoch; only successful return installs it. Failed or uncertain
     * publication leaves the epoch unchanged for durable reread recovery.
     */
    suspend fun publishProjection(
        projection: ProjectionStore,
    ): ProjectionPublicationResult {
        if (preparedPermit != null) {
            throw IllegalStateException("Private prepared runs cannot publish projections")
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Projection publication requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            requireAdmitted()
            if (!projection.bindsTo(database, accountId, sessionGeneration)) {
                throw IllegalArgumentException("Projection publisher is not paired to this run")
            }
            // Conservative capability retirement before invoking the paired
            // publisher; never restored. No durable cleanup: failures require
            // durable reread, never reuse of an old checkpoint token.
            currentPullCheckpoint = null
            currentSnapshotStage = null
            currentCoroutineContext().ensureActive()
            val result = projection.publish(expectedCacheEpoch)
            currentCoroutineContext().ensureActive()
            requireAdmitted()
            expectedCacheEpoch = result.cacheEpoch
            result
        }
    }

    /**
     * Explicit internal paired completion lock for inert prepared promotion.
     * Serializes the entire barrier acquisition plus the caller's combined
     * Room publication/activation transaction on the existing handling Mutex
     * under the already-held shared owner. Verifies the run, requires no
     * enclosing Room transaction, checks publisher pairing with the SAME
     * physical database/account/session, and conservatively retires the prior
     * checkpoint/stage process-local capabilities before the block without
     * durable cleanup; failures require durable reread rather than reusing an
     * old token after an uncertain attempt. The block acquires the required
     * publication barrier outside Room and runs the combined Room transaction
     * (paired prepared publication plus journal/device activation) before
     * returning its result. No new owner, barrier or permissive lease is
     * introduced. Only successful return may be treated as committed.
     */
    internal suspend fun <T> withPreparedCompletionLock(
        projection: ProjectionStore,
        permit: PreparedSessionEnginePermit,
        block: suspend () -> T,
    ): T {
        if (preparedPermit == null || preparedPermit !== permit) {
            throw IllegalStateException("Prepared completion requires its prepared run and permit")
        }
        requireAdmitted()
        if (database.inTransaction()) {
            throw IllegalStateException("Prepared completion requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        return handlingMutex.withLock {
            requireAdmitted()
            if (!projection.bindsTo(database, accountId, sessionGeneration)) {
                throw IllegalArgumentException("Projection publisher is not paired to this run")
            }
            // Conservative capability retirement before the paired barrier and
            // combined Room work; never restored. No durable cleanup.
            currentPullCheckpoint = null
            currentSnapshotStage = null
            currentCoroutineContext().ensureActive()
            val result = block()
            currentCoroutineContext().ensureActive()
            requireAdmitted()
            result
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
