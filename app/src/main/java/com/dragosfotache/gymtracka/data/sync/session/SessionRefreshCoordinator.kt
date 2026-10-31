package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unused application-owned refresh machinery over the accepted encrypted
 * vault and [ServiceAuthApi]. Serializes selection and rotation on one
 * shared run mutex, persists every uncertain attempt before transmission,
 * settles a retained pending attempt first, persists each complete returned
 * grant before use, and fences stale responses with durable revision CAS
 * plus the explicitly revocable in-memory lease.
 *
 * No success cache exists: every selection reloads the durable vault. The
 * run mutex may span HTTP so concurrent callers on the ONE shared lease
 * rotate at most once; callbacks always run outside it. Every seam return
 * rechecks caller activity and lease validity before the result is used, so
 * a successful load, rotation commit, clock read, or UUID generation never
 * outlives an invalidated lease or a cancelled caller; only a CAS already
 * in progress may finish after invalidation, and its result is never
 * admitted under the dead lease. Nothing constructs this in the app;
 * production activation must reconcile both stores first. No constructor
 * I/O, key, or HTTP work happens here.
 */
internal class SessionRefreshCoordinator(
    private val origin: ServiceOrigin,
    private val vault: SessionVault,
    private val authApi: ServiceAuthApi,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val mutex: Mutex = Mutex(),
) {
    /**
     * Selects the exact tagged access credential for an explicit matching
     * lease: live access is returned as committed, otherwise one serialized
     * pending-first rotation runs (at most two refresh transmissions).
     */
    suspend fun accessToken(lease: SessionRefreshLease): BoundServiceAccessToken {
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        checkOrigin(lease)
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            checkAdmission(lease)
            val observation = selectLocked(lease)
            checkAdmission(lease)
            observation.token
        }
    }

    /**
     * Runs [request] as a frozen transport operation with the selected
     * credential, released from the run mutex. Only the first
     * [SyncApiException.AuthenticationRequired] permits one serialized
     * recovery and a single retry of the SAME callback; every other outcome
     * is fenced and surfaced without resend. Durable identity (not strict
     * prior revision) gates admission and result return, so a same-session
     * concurrent rotation need not invalidate an accepted sync result.
     */
    suspend fun <T> withAccessToken(
        lease: SessionRefreshLease,
        request: suspend (BoundServiceAccessToken) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        checkOrigin(lease)
        var observation = mutex.withLock {
            currentCoroutineContext().ensureActive()
            checkAdmission(lease)
            selectLocked(lease)
        }
        checkDurableIdentity(lease)
        var attempts = 0
        while (true) {
            attempts += 1
            currentCoroutineContext().ensureActive()
            checkAdmission(lease)
            val result: T
            try {
                result = request(observation.token)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: SyncApiException.AuthenticationRequired) {
                currentCoroutineContext().ensureActive()
                if (attempts >= 2) {
                    currentCoroutineContext().ensureActive()
                    lease.latchReauthentication()
                    checkSuperseded(lease)
                    throw SyncApiException.ReauthenticationRequired()
                }
                observation = recoverLocked(lease, observation)
                checkDurableIdentity(lease)
                continue
            } catch (e: SyncApiException.ReauthenticationRequired) {
                currentCoroutineContext().ensureActive()
                lease.latchReauthentication()
                checkSuperseded(lease)
                throw e
            } catch (e: SyncApiException) {
                checkSuperseded(lease)
                throw e
            } catch (e: Exception) {
                checkSuperseded(lease)
                throw SyncApiException.Transient()
            }
            currentCoroutineContext().ensureActive()
            checkAdmission(lease)
            checkDurableIdentity(lease)
            return result
        }
    }

    /**
     * Pure compatibility check for pending-refresh reconciliation exact-pending-refresh reconciliation:
     * the supplied reauthentication owner must share this coordinator's
     * configured origin value plus its referentially identical vault and
     * credential mutex. Performs no I/O, clock, or UUID work.
     */
    internal fun isCompatibleRefreshOwner(
        origin: ServiceOrigin,
        vault: SessionVault,
        credentialMutex: Mutex,
    ): Boolean = this.origin == origin && this.vault === vault && this.mutex === credentialMutex

    /**
     * Bounded exact-pending replay under the credential mutex: checks lease
     * origin, caller activity, and atomic lease authorization/admission, takes
     * the preflight exact vault snapshot, replays its retained typed attempt
     * at most ONCE via the private send/commit helpers, proves the stored
     * revision advanced by exactly one with no retry, then invokes settled
     * while still holding the mutex. A null pending performs no HTTP, CAS,
     * clock, or UUID work. Vault revision space is checked before any
     * transmission. No fresh rotation, expiry inference, or token escape:
     * credentials never leave the private send/commit seam. A post-settled
     * lease/caller authorization check runs before release on both the
     * null-pending and replayed paths. Any non-cancellation failure is routed
     * to the failed callback while still holding the mutex, so the caller's
     * error rereads stay under both shared owners; cancellation propagates
     * unchanged without invoking it. The only consumer is inert
     * pending-refresh reconciliation; ordinary selection/rotation entry points
     * are never used here.
     */
    internal suspend fun <T> withExactPendingRefresh(
        lease: SessionRefreshLease,
        preflight: suspend () -> VaultSnapshot,
        settled: suspend (VaultSnapshot) -> T,
        failed: suspend (Throwable) -> Nothing,
    ): T {
        currentCoroutineContext().ensureActive()
        if (lease.binding.origin != origin) {
            currentCoroutineContext().ensureActive()
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        return mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                checkAdmission(lease)
                val snapshot = preflight()
                val record = fenceRecord(lease, snapshot)
                currentCoroutineContext().ensureActive()
                checkAdmission(lease)
                val pending = record.pendingRefresh
                if (pending == null) {
                    val result = settled(snapshot)
                    currentCoroutineContext().ensureActive()
                    checkAdmission(lease)
                    return@withLock result
                }
                if (snapshot.revision == Long.MAX_VALUE) {
                    currentCoroutineContext().ensureActive()
                    throw SessionVaultException(VaultFailure.REVISION_EXHAUSTED)
                }
                val grant = sendRefresh(lease, pending.attempt)
                val stored = commitGrant(lease, snapshot.revision, record, pending, grant)
                currentCoroutineContext().ensureActive()
                if (stored.revision != snapshot.revision + 1L) {
                    currentCoroutineContext().ensureActive()
                    throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
                }
                checkAdmission(lease)
                val result = settled(VaultSnapshot(stored.revision, stored.record))
                currentCoroutineContext().ensureActive()
                checkAdmission(lease)
                return@withLock result
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                failed(e)
            }
        }
    }

    override fun toString(): String = "SessionRefreshCoordinator(redacted)"

    /** One serialized selection: pending-first settle, live-access return, or one fresh rotation. */
    private suspend fun selectLocked(lease: SessionRefreshLease): Observation {
        val snapshot = loadVault(lease)
        val record = fenceRecord(lease, snapshot)
        val pending = record.pendingRefresh
        if (pending != null) {
            return settlePendingLocked(lease, snapshot.revision, record, pending)
        }
        val now = readClock(lease)
        if (record.session.accessExpiresAtMillis > now) {
            return Observation(tagCredential(lease, record), snapshot.revision)
        }
        if (record.session.refreshExpiresAtMillis <= now) {
            lease.latchReauthentication()
            currentCoroutineContext().ensureActive()
            throw SyncApiException.ReauthenticationRequired()
        }
        return rotateFreshLocked(lease, snapshot.revision, record)
    }

    /**
     * Replays the retained EXACT attempt (no UUID calls, recapture, or
     * reserialization), persists its complete grant first, then rotates once
     * more from the returned CURRENT refresh only if access remains expired.
     */
    private suspend fun settlePendingLocked(
        lease: SessionRefreshLease,
        revision: Long,
        record: VaultSessionRecord,
        pending: VaultPendingRefresh,
    ): Observation {
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        val grant = sendRefresh(lease, pending.attempt)
        val stored = commitGrant(lease, revision, record, pending, grant)
        val now = readClock(lease)
        if (stored.record.session.accessExpiresAtMillis > now) {
            return Observation(tagCredential(lease, stored.record), stored.revision)
        }
        if (stored.record.session.refreshExpiresAtMillis <= now) {
            lease.latchReauthentication()
            currentCoroutineContext().ensureActive()
            throw SyncApiException.ReauthenticationRequired()
        }
        return rotateFreshLocked(lease, stored.revision, stored.record)
    }

    /**
     * Mints exactly one uncertain attempt (pending-operation UUID then key,
     * body captured ONCE), CAS-persists it before any send, reload-verifies
     * the exact sent revision/record/attempt plus a live lease, transmits
     * once, then commits the complete grant. Expired resulting access stops
     * with ACCESS_UNAVAILABLE; the committed grant is preserved.
     */
    private suspend fun rotateFreshLocked(
        lease: SessionRefreshLease,
        baseRevision: Long,
        baseRecord: VaultSessionRecord,
    ): Observation {
        val pendingOperationId = nextUuid(lease)
        val idempotencyKey = nextUuid(lease)
        val attempt = captureAttempt(lease, idempotencyKey, baseRecord.session.refreshToken)
        val pending = VaultPendingRefresh(pendingOperationId, attempt)
        val pendingRecord = baseRecord.copy(pendingRefresh = pending)
        checkAdmission(lease)
        val committed = casVault(lease, baseRevision, pendingRecord)
        val sent = loadVault(lease)
        val sentRecord = sent.record
        val sentPending = sentRecord?.pendingRefresh
        if (sent.revision != committed.revision ||
            sentRecord == null ||
            sentPending == null ||
            !pendingEquals(sentPending, pending) ||
            sentRecord.binding != pendingRecord.binding ||
            sentRecord.operationId != pendingRecord.operationId ||
            sentRecord.phase != pendingRecord.phase ||
            sentRecord.session != pendingRecord.session ||
            sentRecord.supersededSessions != pendingRecord.supersededSessions
        ) {
            currentCoroutineContext().ensureActive()
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        val grant = sendRefresh(lease, sentPending.attempt)
        val stored = commitGrant(lease, sent.revision, sentRecord, sentPending, grant)
        val now = readClock(lease)
        if (stored.record.session.accessExpiresAtMillis > now) {
            return Observation(tagCredential(lease, stored.record), stored.revision)
        }
        currentCoroutineContext().ensureActive()
        throw SessionRefreshException(SessionRefreshFailure.ACCESS_UNAVAILABLE)
    }

    /**
     * Persists the complete returned grant with cleared pending at exactly
     * the sent revision, preserving operation and phase, then reload-fences
     * the commit. Account mismatch keeps the uncertain pair via
     * MalformedResponse. Invalidation during the pre-save load stops before
     * any CAS begins; only a CAS already in progress may finish after
     * invalidation, and its result is never admitted. Stale-result CAS is
     * never retried.
     */
    private suspend fun commitGrant(
        lease: SessionRefreshLease,
        sentRevision: Long,
        sentRecord: VaultSessionRecord,
        sentPending: VaultPendingRefresh,
        grant: ServiceSession,
    ): StoredGrant {
        currentCoroutineContext().ensureActive()
        if (grant.account.accountId != sentRecord.binding.accountId) {
            checkSuperseded(lease)
            currentCoroutineContext().ensureActive()
            throw SyncApiException.MalformedResponse()
        }
        checkAdmission(lease)
        val current = loadVault(lease)
        val currentRecord = current.record
        val currentPending = currentRecord?.pendingRefresh
        if (current.revision != sentRevision ||
            currentRecord == null ||
            currentPending == null ||
            !pendingEquals(currentPending, sentPending) ||
            currentRecord.binding != sentRecord.binding ||
            currentRecord.operationId != sentRecord.operationId ||
            currentRecord.phase != sentRecord.phase ||
            currentRecord.session != sentRecord.session ||
            currentRecord.supersededSessions != sentRecord.supersededSessions
        ) {
            currentCoroutineContext().ensureActive()
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        val finalRecord = sentRecord.copy(session = grant, pendingRefresh = null)
        checkAdmission(lease)
        val committed = casVault(lease, sentRevision, finalRecord)
        val fenced = loadVault(lease)
        val provenRecord = fenced.record
        if (fenced.revision != committed.revision ||
            provenRecord == null ||
            provenRecord.binding != finalRecord.binding ||
            provenRecord.operationId != finalRecord.operationId ||
            provenRecord.phase != finalRecord.phase ||
            provenRecord.session != finalRecord.session ||
            provenRecord.pendingRefresh != null ||
            provenRecord.supersededSessions != finalRecord.supersededSessions
        ) {
            currentCoroutineContext().ensureActive()
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        return StoredGrant(provenRecord, fenced.revision)
    }

    /**
     * Single refresh transmission with closed error mapping. The uncertain
     * pair and original grant stay retained (except a complete uncertain
     * commit); safe categories and hints propagate as the same instance;
     * wrong-context access denial and explicit reauth latch foreground
     * reauthentication; unexpected seam errors become closed Transient.
     */
    private suspend fun sendRefresh(
        lease: SessionRefreshLease,
        attempt: ServiceRefreshAttempt,
    ): ServiceSession {
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        try {
            return authApi.refresh(attempt)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SyncApiException.ReauthenticationRequired) {
            currentCoroutineContext().ensureActive()
            lease.latchReauthentication()
            checkSuperseded(lease)
            throw e
        } catch (e: SyncApiException.AuthenticationRequired) {
            currentCoroutineContext().ensureActive()
            lease.latchReauthentication()
            checkSuperseded(lease)
            throw SyncApiException.ReauthenticationRequired()
        } catch (e: SyncApiException) {
            checkSuperseded(lease)
            throw e
        } catch (e: Exception) {
            checkSuperseded(lease)
            throw SyncApiException.Transient()
        }
    }

    /**
     * One serialized recovery after the first access rejection: reuses a
     * newer no-pending usable grant installed after the failed observation,
     * otherwise settles pending or forces one fresh refresh even when the
     * rejected access time appears live. Refresh failure prevents retry.
     */
    private suspend fun recoverLocked(
        lease: SessionRefreshLease,
        failed: Observation,
    ): Observation =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            checkAdmission(lease)
            val snapshot = loadVault(lease)
            val record = fenceRecord(lease, snapshot)
            if (snapshot.revision > failed.revision && record.pendingRefresh == null) {
                val now = readClock(lease)
                if (record.session.accessExpiresAtMillis > now) {
                    return@withLock Observation(tagCredential(lease, record), snapshot.revision)
                }
            }
            val pending = record.pendingRefresh
            if (pending != null) {
                return@withLock settlePendingLocked(lease, snapshot.revision, record, pending)
            }
            val now = readClock(lease)
            if (record.session.refreshExpiresAtMillis <= now) {
                lease.latchReauthentication()
                currentCoroutineContext().ensureActive()
                throw SyncApiException.ReauthenticationRequired()
            }
            return@withLock rotateFreshLocked(lease, snapshot.revision, record)
        }

    /**
     * Late-error fence: caller cancellation dominates first then an
     * invalidated lease or a superseded durable identity (tombstoned,
     * replaced, or re-bound record) overrides the old operation's category
     * with STALE_SESSION. The load itself is fenced, and lease/caller are
     * rechecked after it. Returns normally when the old category applies, so
     * active-context safe failures keep their identity; never blindly
     * rethrows late errors.
     */
    private suspend fun checkSuperseded(lease: SessionRefreshLease) {
        currentCoroutineContext().ensureActive()
        if (lease.isInvalidated()) {
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        val current = loadVault(lease)
        fenceRecord(lease, current)
        postSeam(lease)
    }

    /**
     * Caller activity plus atomic lease admission from one snapshot:
     * invalidated stops as stale, latched needs foreground. Never splits
     * the observation across two reads.
     */
    private suspend fun checkAdmission(lease: SessionRefreshLease) {
        currentCoroutineContext().ensureActive()
        lease.checkAuthorization()
        currentCoroutineContext().ensureActive()
        lease.checkAdmission()
    }

    /** The lease origin must equal the configured origin; never send across origins. */
    private suspend fun checkOrigin(lease: SessionRefreshLease) {
        if (lease.binding.origin != origin) {
            currentCoroutineContext().ensureActive()
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
    }

    /**
     * Reloads durable identity for callback admission and result return;
     * strict revision is not required. Lease and caller are rechecked after
     * the load, so death during admission stops before the callback runs.
     */
    private suspend fun checkDurableIdentity(lease: SessionRefreshLease) {
        currentCoroutineContext().ensureActive()
        checkAdmission(lease)
        fenceRecord(lease, loadVault(lease))
        checkAdmission(lease)
    }

    /** Record must exist and match the lease binding, operation, and phase exactly; never adopts another. */
    private suspend fun fenceRecord(
        lease: SessionRefreshLease,
        snapshot: VaultSnapshot,
    ): VaultSessionRecord {
        val record = snapshot.record
        if (record == null ||
            record.binding != lease.binding ||
            record.operationId != lease.operationId ||
            record.phase != lease.phase
        ) {
            currentCoroutineContext().ensureActive()
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
        return record
    }

    /**
     * Post-seam recheck applied to every successful seam return and before
     * surfacing mapped seam failures: caller cancellation dominates first,
     * then an invalidated lease overrides with STALE_SESSION. Latched leases
     * are left to explicit admission points so fenced safe errors keep their
     * identity.
     */
    private suspend fun postSeam(lease: SessionRefreshLease) {
        currentCoroutineContext().ensureActive()
        lease.checkAuthorization()
        currentCoroutineContext().ensureActive()
        if (lease.isInvalidated()) {
            throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
        }
    }

    private suspend fun loadVault(lease: SessionRefreshLease): VaultSnapshot {
        try {
            val snapshot = vault.load()
            postSeam(lease)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    private suspend fun casVault(
        lease: SessionRefreshLease,
        expectedRevision: Long,
        record: VaultSessionRecord?,
    ): VaultSnapshot {
        try {
            val snapshot = vault.compareAndSet(expectedRevision, record)
            postSeam(lease)
            return snapshot
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: SessionVaultException) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    private suspend fun readClock(lease: SessionRefreshLease): Long {
        try {
            val now = clockMillis()
            postSeam(lease)
            return now
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
        }
    }

    private suspend fun nextUuid(lease: SessionRefreshLease): String {
        val raw: String
        try {
            raw = uuidProvider()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
        }
        try {
            requireWorkoutUuid(raw, "id")
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw SessionRefreshException(SessionRefreshFailure.INVALID_INPUT)
        }
        postSeam(lease)
        return raw
    }

    private suspend fun captureAttempt(
        lease: SessionRefreshLease,
        idempotencyKey: String,
        refreshToken: OpaqueToken,
    ): ServiceRefreshAttempt {
        try {
            val attempt = ServiceRefreshAttempt.capture(idempotencyKey, refreshToken)
            postSeam(lease)
            return attempt
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (lease.isInvalidated()) {
                throw SessionRefreshException(SessionRefreshFailure.STALE_SESSION)
            }
            throw SessionRefreshException(SessionRefreshFailure.UNAVAILABLE)
        }
    }

    private fun tagCredential(
        lease: SessionRefreshLease,
        record: VaultSessionRecord,
    ): BoundServiceAccessToken = BoundServiceAccessToken(lease.binding, record.session.accessToken)

    /** Exact uncertain-attempt identity: operation, key, immutable body text, and token. */
    private fun pendingEquals(actual: VaultPendingRefresh, expected: VaultPendingRefresh): Boolean =
        actual.operationId == expected.operationId &&
            actual.attempt.idempotencyKey == expected.attempt.idempotencyKey &&
            actual.attempt.requestJson == expected.attempt.requestJson &&
            actual.attempt.refreshToken == expected.attempt.refreshToken

    /** Selected credential plus the vault revision it was observed at. */
    private data class Observation(
        val token: BoundServiceAccessToken,
        val revision: Long,
    )

    /** Committed grant plus the vault revision it was proven at. */
    private data class StoredGrant(
        val record: VaultSessionRecord,
        val revision: Long,
    )
}
