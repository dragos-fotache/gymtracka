package com.dragosfotache.gymtracka.data.sync.session

import android.database.Cursor
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncAccountCacheEntity
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.SyncSessionAuthorityDao
import com.dragosfotache.gymtracka.data.gym.SyncSessionAuthorityEntity
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Inert real-Room [SessionRecoveryAuthority] adapter with atomic private
 * preparation/fencing over the independent session-authority journal.
 *
 * Every operation reads the journal, device and selected cache together in one
 * real Room transaction with strict SQLite storage-type inspection: REAL or
 * TEXT numerics are never coerced into counters/booleans, extra singleton rows
 * and inconsistent device/journal identity fail closed as corruption, and no
 * origin/operation/ACTIVE authority is ever inferred from old device/cache rows.
 *
 * Reservation ([beginPreparation]) and durable private invalidation
 * ([fencePrivate]) write the journal plus the device singleton atomically with
 * affected-count fenced updates and exact post-write rechecks before commit.
 * The explicit internal completion writer additionally requires a registered
 * prepared permit and the caller's publication transaction before writing
 * ACTIVE/ready. No secrets, clocks, UUID generation, network, vault I/O or
 * app-wide scope live here. Transaction cancellation/failure is not a
 * rollback/success claim after an uncertain return: callers must reread durable
 * state. No process success cache exists.
 */
internal class RoomSessionAuthority(private val database: GymDatabase) : SessionPreparationAuthority,
    SessionReauthenticationAuthority {
    override suspend fun read(): RecoveryRoomSnapshot =
        transact {
            val observed = observeLocked(database.syncSessionAuthorityDao())
            currentCoroutineContext().ensureActive()
            observed.snapshot
        }

    /**
     * Reserves one private preparation from the exact [expected] signed-out
     * observation. This is reservation, not the whole same-account reauth
     * implementation: it refuses a different retained account, an unknown or
     * different durable origin provenance, and any local-only root graph that
     * still requires an owner data-disposition decision.
     */
    override suspend fun beginPreparation(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot {
        currentCoroutineContext().ensureActive()
        val targetAccount = requireUuid(accountId)
        val targetOperation = requireUuid(operationId)
        val originText = canonicalOriginText(origin)
        return transact {
            val dao = database.syncSessionAuthorityDao()
            val observed = observeLocked(dao)
            if (observed.snapshot != expected) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            if (observed.snapshot.phase != RecoveryRoomPhase.SIGNED_OUT) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val cached = observed.snapshot.cachedAccountId
            if (cached != null && cached != targetAccount) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
            }
            if (cached != null) {
                val provenance = observed.journal?.serviceOrigin
                if (provenance == null || provenance != originText) {
                    throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
                }
            }
            if (hasLocalOnlyRoots(dao)) {
                throw RoomSessionAuthorityException(
                    RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
                )
            }
            val newRevision = checkedNext(observed.snapshot.revision)
            val newGeneration = checkedNext(observed.snapshot.sessionGeneration)
            val expectedJournal = RawJournalRow(
                revision = newRevision,
                sessionGeneration = newGeneration,
                phase = PREPARING_PHASE,
                serviceOrigin = originText,
                accountId = targetAccount,
                operationId = targetOperation,
            )
            val expectedDevice = RawDeviceRow(
                activeAccountId = null,
                cachedAccountId = targetAccount,
                sessionGeneration = newGeneration,
                cacheState = PREPARING_CACHE_STATE,
            )
            writeJournalPreparing(
                dao,
                observed.journal,
                newRevision,
                newGeneration,
                originText,
                targetAccount,
                targetOperation,
            )
            writeDevice(
                dao,
                observed.device,
                newActiveAccountId = null,
                newCachedAccountId = targetAccount,
                newGeneration = newGeneration,
                newCacheState = PREPARING_CACHE_STATE,
            )
            val reread = observeLocked(dao)
            if (reread.journal != expectedJournal || reread.device != expectedDevice) {
                throw corrupt()
            }
            val prepared = RecoveryRoomSnapshot(
                revision = newRevision,
                sessionGeneration = newGeneration,
                cachedAccountId = targetAccount,
                phase = RecoveryRoomPhase.PREPARING,
                identity = RecoveryIdentity(
                    binding = ServiceSessionBinding(
                        origin = origin,
                        accountId = targetAccount,
                        sessionGeneration = newGeneration,
                    ),
                    operationId = targetOperation,
                ),
                baselineComplete = false,
            )
            if (reread.snapshot != prepared) {
                throw corrupt()
            }
            currentCoroutineContext().ensureActive()
            reread.snapshot
        }
    }

    /**
     * Reserves one private same-account reauthentication from the exact [expected]
     * settled occupied ACTIVE observation. Reservation only, not activation,
     * promotion, vault replacement or cleanup: refuses a different retained
     * account, unknown or different origin provenance, a reused operation, an
     * incomplete baseline, any local-only root graph still requiring an owner
     * decision, and insufficient revision space for both reservation and the
     * later finalization. Does not inspect the vault.
     *
     * Atomically writes journal TRANSITIONING at revision+1/generation+1 with
     * the same origin/account and the fresh operation, plus device
     * active=null/cached=same/generation=new/cache_state=preparing, with
     * affected-count fenced updates and exact post-write rechecks before commit.
     * Returns the exact TRANSITIONING snapshot with baselineComplete=false.
     * Preserves every other table and sequence. Never compensates via
     * [fencePrivate] or [beginPreparation] and never restarts the generation.
     */
    override suspend fun beginReauthentication(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        operationId: String,
    ): RecoveryRoomSnapshot {
        currentCoroutineContext().ensureActive()
        val targetOperation = requireUuid(operationId)
        val originText = canonicalOriginText(origin)
        return transact {
            val dao = database.syncSessionAuthorityDao()
            val observed = observeLocked(dao)
            if (observed.snapshot != expected) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            if (observed.snapshot.phase != RecoveryRoomPhase.ACTIVE) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            if (!observed.snapshot.baselineComplete) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val identity = observed.snapshot.identity ?: throw corrupt()
            if (identity.binding.origin != origin) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
            }
            if (identity.operationId == targetOperation) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            if (observed.snapshot.revision > Long.MAX_VALUE - 2L) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.COUNTER_OVERFLOW)
            }
            val retainedAccount = identity.binding.accountId
            val journal = observed.journal ?: throw corrupt()
            if (journal.serviceOrigin != originText || journal.accountId != retainedAccount) {
                throw corrupt()
            }
            val device = observed.device ?: throw corrupt()
            if (device.activeAccountId != retainedAccount ||
                device.cachedAccountId != retainedAccount ||
                device.sessionGeneration != observed.snapshot.sessionGeneration ||
                device.cacheState != READY_CACHE_STATE
            ) {
                throw corrupt()
            }
            if (hasLocalOnlyRoots(dao)) {
                throw RoomSessionAuthorityException(
                    RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
                )
            }
            val newRevision = checkedNext(observed.snapshot.revision)
            val newGeneration = checkedNext(observed.snapshot.sessionGeneration)
            val expectedJournal = RawJournalRow(
                revision = newRevision,
                sessionGeneration = newGeneration,
                phase = TRANSITIONING_PHASE,
                serviceOrigin = originText,
                accountId = retainedAccount,
                operationId = targetOperation,
            )
            val expectedDevice = RawDeviceRow(
                activeAccountId = null,
                cachedAccountId = retainedAccount,
                sessionGeneration = newGeneration,
                cacheState = PREPARING_CACHE_STATE,
            )
            writeJournalTransitioning(
                dao,
                observed.journal,
                newRevision,
                newGeneration,
                originText,
                retainedAccount,
                targetOperation,
            )
            writeDevice(
                dao,
                observed.device,
                newActiveAccountId = null,
                newCachedAccountId = retainedAccount,
                newGeneration = newGeneration,
                newCacheState = PREPARING_CACHE_STATE,
            )
            val reread = observeLocked(dao)
            if (reread.journal != expectedJournal || reread.device != expectedDevice) {
                throw corrupt()
            }
            val transitioning = RecoveryRoomSnapshot(
                revision = newRevision,
                sessionGeneration = newGeneration,
                cachedAccountId = retainedAccount,
                phase = RecoveryRoomPhase.TRANSITIONING,
                identity = RecoveryIdentity(
                    binding = ServiceSessionBinding(
                        origin = origin,
                        accountId = retainedAccount,
                        sessionGeneration = newGeneration,
                    ),
                    operationId = targetOperation,
                ),
                baselineComplete = false,
            )
            if (reread.snapshot != transitioning) {
                throw corrupt()
            }
            currentCoroutineContext().ensureActive()
            reread.snapshot
        }
    }

    /**
     * Finalizes one exact private TRANSITIONING reservation to PREPARING.
     * Admits only the exact [expected] TRANSITIONING identity plus device
     * active=null/cached=target/generation-exact/cache_state=preparing;
     * refuses local-only roots appearing after reservation. Increments the
     * checked journal revision ONCE while retaining generation, origin,
     * account and operation, writes phase preparing and leaves the exact
     * preparing device tuple unchanged, with affected-count fencing and exact
     * post-write rechecks before commit. No domain/cache/outbox/stage/
     * checkpoint/catalogue writes. Preserves every other table and sequence.
     */
    override suspend fun finishReauthentication(
        expected: RecoveryRoomSnapshot,
    ): RecoveryRoomSnapshot {
        currentCoroutineContext().ensureActive()
        return transact {
            val dao = database.syncSessionAuthorityDao()
            val observed = observeLocked(dao)
            if (observed.snapshot != expected) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            if (observed.snapshot.phase != RecoveryRoomPhase.TRANSITIONING) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val identity = observed.snapshot.identity ?: throw corrupt()
            if (observed.snapshot.baselineComplete) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val device = observed.device ?: throw corrupt()
            if (device.activeAccountId != null ||
                device.cachedAccountId != identity.binding.accountId ||
                device.sessionGeneration != observed.snapshot.sessionGeneration ||
                device.cacheState != PREPARING_CACHE_STATE
            ) {
                throw corrupt()
            }
            if (hasLocalOnlyRoots(dao)) {
                throw RoomSessionAuthorityException(
                    RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
                )
            }
            val previousJournal = readJournal(dao)
                ?: throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            val previousDevice = readDevice(dao)
                ?: throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            val newRevision = checkedNext(previousJournal.revision)
            val affectedJournal = dao.updateAuthority(
                newRevision = newRevision,
                newGeneration = previousJournal.sessionGeneration,
                newPhase = PREPARING_PHASE,
                newOrigin = previousJournal.serviceOrigin,
                newAccountId = previousJournal.accountId,
                newOperationId = previousJournal.operationId,
                expectedRevision = previousJournal.revision,
                expectedGeneration = previousJournal.sessionGeneration,
                expectedPhase = previousJournal.phase,
                expectedOrigin = previousJournal.serviceOrigin,
                expectedAccountId = previousJournal.accountId,
                expectedOperationId = previousJournal.operationId,
            )
            if (affectedJournal != 1) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val reread = observeLocked(dao)
            val expectedJournal = previousJournal.copy(
                revision = newRevision,
                phase = PREPARING_PHASE,
            )
            if (reread.journal != expectedJournal || reread.device != previousDevice) {
                throw corrupt()
            }
            val preparing = RecoveryRoomSnapshot(
                revision = newRevision,
                sessionGeneration = previousJournal.sessionGeneration,
                cachedAccountId = previousJournal.accountId,
                phase = RecoveryRoomPhase.PREPARING,
                identity = RecoveryIdentity(
                    binding = ServiceSessionBinding(
                        origin = identity.binding.origin,
                        accountId = identity.binding.accountId,
                        sessionGeneration = previousJournal.sessionGeneration,
                    ),
                    operationId = identity.operationId,
                ),
                baselineComplete = false,
            )
            if (reread.snapshot != preparing) {
                throw corrupt()
            }
            currentCoroutineContext().ensureActive()
            reread.snapshot
        }
    }

    /**
     * Durably fences private authority from the exact [expected] observation in
     * any recognized phase, including fresh signed-out. This is private
     * invalidation, not successful sign-out, vault cleanup, pending-operation
     * abandonment or account deletion: cached/domain/outbox/stage/cursor/
     * baseline/watermark/catalogue data and exact uncertain bytes are preserved.
     */
    suspend fun fencePrivate(expected: RecoveryRoomSnapshot): RecoveryRoomSnapshot {
        currentCoroutineContext().ensureActive()
        return transact {
            val dao = database.syncSessionAuthorityDao()
            val observed = observeLocked(dao)
            if (observed.snapshot != expected) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
            val cached = observed.snapshot.cachedAccountId
            val retainedOrigin = observed.journal?.serviceOrigin
            val newRevision = checkedNext(observed.snapshot.revision)
            val newGeneration = checkedNext(observed.snapshot.sessionGeneration)
            val expectedJournal = RawJournalRow(
                revision = newRevision,
                sessionGeneration = newGeneration,
                phase = SIGNED_OUT_PHASE,
                serviceOrigin = retainedOrigin,
                accountId = cached,
                operationId = null,
            )
            val expectedDevice = RawDeviceRow(
                activeAccountId = null,
                cachedAccountId = cached,
                sessionGeneration = newGeneration,
                cacheState = PRIVATE_CACHE_STATE,
            )
            writeJournalSignedOut(
                dao,
                observed.journal,
                newRevision,
                newGeneration,
                retainedOrigin,
                cached,
            )
            writeDevice(
                dao,
                observed.device,
                newActiveAccountId = null,
                newCachedAccountId = cached,
                newGeneration = newGeneration,
                newCacheState = PRIVATE_CACHE_STATE,
            )
            val reread = observeLocked(dao)
            if (reread.journal != expectedJournal || reread.device != expectedDevice) {
                throw corrupt()
            }
            val fenced = RecoveryRoomSnapshot(
                revision = newRevision,
                sessionGeneration = newGeneration,
                cachedAccountId = cached,
                phase = RecoveryRoomPhase.SIGNED_OUT,
                identity = null,
                baselineComplete = false,
            )
            if (reread.snapshot != fenced) {
                throw corrupt()
            }
            currentCoroutineContext().ensureActive()
            reread.snapshot
        }
    }

    /**
     * Rechecks one exact private PREPARING permit inside the caller's
     * active Room transaction, before effects and after writes before
     * commit. Reuses the strict [observeLocked] observation, including
     * canonical origin parsing and corruption refusal: extra rows, storage
     * type coercion, unknown phases and inconsistent tuples fail closed.
     * Requires the journal revision/identity to match the permit exactly,
     * the device singleton to carry active=null, the cached target, the
     * exact generation and cacheState "preparing", and the creating
     * process invocation to still be current. Must run inside the caller's
     * transaction; never opens its own transaction and performs no
     * vault/HTTP/clock work. Failures keep their closed authority
     * categories for the caller to map.
     */
    internal suspend fun checkPreparedInTransaction(permit: PreparedSessionEnginePermit) {
        currentCoroutineContext().ensureActive()
        if (permit.database !== database) {
            throw IllegalStateException("Prepared permit belongs to another database")
        }
        if (!database.inTransaction()) {
            throw IllegalStateException("Private preparation check requires an active transaction")
        }
        val observed = observeLocked(database.syncSessionAuthorityDao())
        val snapshot = observed.snapshot
        if (snapshot.phase != RecoveryRoomPhase.PREPARING) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val identity = snapshot.identity ?: throw corrupt()
        if (identity.binding.origin != permit.origin ||
            identity.binding.accountId != permit.accountId ||
            identity.binding.sessionGeneration != permit.sessionGeneration ||
            identity.operationId != permit.operationId
        ) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
        }
        if (snapshot.revision != permit.journalRevision) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val device = observed.device ?: throw corrupt()
        if (device.activeAccountId != null ||
            device.cachedAccountId != permit.accountId ||
            device.sessionGeneration != permit.sessionGeneration ||
            device.cacheState != PREPARING_CACHE_STATE
        ) {
            throw corrupt()
        }
        if (!permit.isCurrent()) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Atomically completes one exact private PREPARING permit to ACTIVE inside
     * the caller's active Room transaction (the same transaction that carries
     * the paired publication graph work, so domain, cache, journal and device
     * roll back together). Must run inside the caller's transaction; never
     * opens its own transaction and performs no vault/HTTP/clock work.
     *
     * Rechecks the exact permit (identity, revision, device preparing tuple
     * and currentness), refuses local-only roots without touching anything,
     * checks revision overflow before any write (avoidable overflow must not
     * create the Room-committed/vault-unpromoted gap), then increments the
     * checked journal revision ONCE while retaining generation, origin,
     * account and operation, writes phase active and device
     * active=cached=target/cache_state=ready with exact whole-old-row
     * predicates and affected-count fencing, and verifies the exact
     * post-write journal/device/snapshot tuple before commit. Returns the new
     * journal revision. Failures keep their closed authority categories.
     */
    internal suspend fun completeToActiveInTransaction(
        permit: PreparedSessionEnginePermit,
    ): Long {
        currentCoroutineContext().ensureActive()
        if (permit.database !== database) {
            throw IllegalStateException("Prepared permit belongs to another database")
        }
        if (!database.inTransaction()) {
            throw IllegalStateException("Private completion requires an active transaction")
        }
        checkPreparedInTransaction(permit)
        val dao = database.syncSessionAuthorityDao()
        if (hasLocalOnlyRoots(dao)) {
            throw RoomSessionAuthorityException(
                RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
            )
        }
        val previousJournal = readJournal(dao)
            ?: throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        val previousDevice = readDevice(dao)
            ?: throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        val newRevision = checkedNext(previousJournal.revision)
        val affectedJournal = dao.updateAuthority(
            newRevision = newRevision,
            newGeneration = previousJournal.sessionGeneration,
            newPhase = ACTIVE_PHASE,
            newOrigin = previousJournal.serviceOrigin,
            newAccountId = previousJournal.accountId,
            newOperationId = previousJournal.operationId,
            expectedRevision = previousJournal.revision,
            expectedGeneration = previousJournal.sessionGeneration,
            expectedPhase = previousJournal.phase,
            expectedOrigin = previousJournal.serviceOrigin,
            expectedAccountId = previousJournal.accountId,
            expectedOperationId = previousJournal.operationId,
        )
        if (affectedJournal != 1) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val affectedDevice = dao.updateDeviceState(
            newActiveAccountId = previousDevice.cachedAccountId,
            newCachedAccountId = previousDevice.cachedAccountId,
            newGeneration = previousDevice.sessionGeneration,
            newCacheState = READY_CACHE_STATE,
            expectedActiveAccountId = previousDevice.activeAccountId,
            expectedCachedAccountId = previousDevice.cachedAccountId,
            expectedGeneration = previousDevice.sessionGeneration,
            expectedCacheState = previousDevice.cacheState,
        )
        if (affectedDevice != 1) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val reread = observeLocked(dao)
        val expectedJournal = previousJournal.copy(
            revision = newRevision,
            phase = ACTIVE_PHASE,
        )
        val expectedDevice = previousDevice.copy(
            activeAccountId = previousDevice.cachedAccountId,
            cacheState = READY_CACHE_STATE,
        )
        if (reread.journal != expectedJournal || reread.device != expectedDevice) {
            throw corrupt()
        }
        val snapshot = reread.snapshot
        if (snapshot.phase != RecoveryRoomPhase.ACTIVE ||
            snapshot.revision != newRevision ||
            snapshot.sessionGeneration != previousJournal.sessionGeneration ||
            snapshot.cachedAccountId != previousJournal.accountId
        ) {
            throw corrupt()
        }
        val identity = snapshot.identity ?: throw corrupt()
        if (identity.binding.accountId != permit.accountId ||
            identity.binding.sessionGeneration != permit.sessionGeneration ||
            identity.operationId != permit.operationId
        ) {
            throw corrupt()
        }
        if (!permit.isCurrent()) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        currentCoroutineContext().ensureActive()
        return newRevision
    }

    /**
     * Re-verifies the exact post-ACTIVE durable tuple inside the caller's
     * active Room transaction after an untrusted callback (the publication
     * lease check) ran between the activation write and commit. Requires
     * the ACTIVE journal at [expectedRevision] with the permit identity,
     * the ready device tuple, no local-only roots and a current permit.
     * Never opens its own transaction and performs no vault/HTTP/clock
     * work. Failures keep their closed authority categories.
     */
    internal suspend fun verifyActiveInTransaction(
        permit: PreparedSessionEnginePermit,
        expectedRevision: Long,
    ) {
        currentCoroutineContext().ensureActive()
        if (permit.database !== database) {
            throw IllegalStateException("Prepared permit belongs to another database")
        }
        if (!database.inTransaction()) {
            throw IllegalStateException("Active verification requires an active transaction")
        }
        val dao = database.syncSessionAuthorityDao()
        val observed = observeLocked(dao)
        val snapshot = observed.snapshot
        if (snapshot.phase != RecoveryRoomPhase.ACTIVE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        if (snapshot.revision != expectedRevision) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val identity = snapshot.identity ?: throw corrupt()
        if (identity.binding.origin != permit.origin ||
            identity.binding.accountId != permit.accountId ||
            identity.binding.sessionGeneration != permit.sessionGeneration ||
            identity.operationId != permit.operationId
        ) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.ACCOUNT_MISMATCH)
        }
        val device = observed.device ?: throw corrupt()
        if (device.activeAccountId != permit.accountId ||
            device.cachedAccountId != permit.accountId ||
            device.sessionGeneration != permit.sessionGeneration ||
            device.cacheState != READY_CACHE_STATE
        ) {
            throw corrupt()
        }
        if (hasLocalOnlyRoots(dao)) {
            throw RoomSessionAuthorityException(
                RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION,
            )
        }
        if (!permit.isCurrent()) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Coherent ACTIVE-ready observation inside the caller's active Room
     * transaction: strict ACTIVE journal with the ready device tuple plus
     * the selected-account cache row, all under the caller's transaction so
     * no authority/cache leg can change between reads. Never opens its own
     * transaction and performs no vault/HTTP/clock work. The caller owns
     * any further same-transaction state checks. Failures keep their closed
     * authority categories.
     */
    internal suspend fun readActiveReadyInTransaction(): ActiveReadyObservation {
        currentCoroutineContext().ensureActive()
        if (!database.inTransaction()) {
            throw IllegalStateException("Active ready read requires an active transaction")
        }
        val dao = database.syncSessionAuthorityDao()
        val observed = observeLocked(dao)
        val snapshot = observed.snapshot
        if (snapshot.phase != RecoveryRoomPhase.ACTIVE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        }
        val identity = snapshot.identity ?: throw corrupt()
        val device = observed.device ?: throw corrupt()
        if (device.activeAccountId != identity.binding.accountId ||
            device.cachedAccountId != identity.binding.accountId ||
            device.sessionGeneration != snapshot.sessionGeneration ||
            device.cacheState != READY_CACHE_STATE
        ) {
            throw corrupt()
        }
        currentCoroutineContext().ensureActive()
        val cache = database.syncCanonicalDao().getAccountCache(identity.binding.accountId)
        currentCoroutineContext().ensureActive()
        return ActiveReadyObservation(snapshot = snapshot, cache = cache)
    }

    /**
     * Strict read-only ACTIVE readiness observation with the full ready
     * device tuple for inert completion recovery. Runs in its own Room
     * transaction; performs no writes, vault, HTTP or clock work.
     *
     * Returns the ACTIVE snapshot with its selected-account cache row (null
     * when absent). The journal must carry phase active with a retained
     * identity; the device singleton must carry active=cached=account, the
     * exact snapshot generation and cache_state ready. Any journal/device
     * inconsistency fails closed as corruption; incomplete cache state is
     * returned (not thrown) for the caller to refuse as NOT_READY.
     */
    internal suspend fun readActiveReady(): ActiveReadyObservation =
        transact {
            readActiveReadyInTransaction()
        }

    override fun toString(): String = "RoomSessionAuthority(redacted)"

    private suspend fun <T> transact(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        try {
            val result = database.withTransaction { block() }
            currentCoroutineContext().ensureActive()
            return result
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: RoomSessionAuthorityException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.UNAVAILABLE)
        }
    }

    private fun hasLocalOnlyRoots(dao: SyncSessionAuthorityDao): Boolean =
        dao.hasLocalOnlyWorkoutRoot() ||
            dao.hasLocalOnlyRoutineRoot() ||
            dao.hasLocalOnlyCustomExerciseRoot()

    private suspend fun writeJournalPreparing(
        dao: SyncSessionAuthorityDao,
        previous: RawJournalRow?,
        newRevision: Long,
        newGeneration: Long,
        originText: String,
        accountId: String,
        operationId: String,
    ) {
        if (previous == null) {
            val rowId = dao.insertAuthority(
                SyncSessionAuthorityEntity(
                    revision = newRevision,
                    sessionGeneration = newGeneration,
                    phase = PREPARING_PHASE,
                    serviceOrigin = originText,
                    accountId = accountId,
                    operationId = operationId,
                ),
            )
            if (rowId != SINGLETON_ROW_ID) {
                throw corrupt()
            }
        } else {
            val affected = dao.updateAuthority(
                newRevision = newRevision,
                newGeneration = newGeneration,
                newPhase = PREPARING_PHASE,
                newOrigin = originText,
                newAccountId = accountId,
                newOperationId = operationId,
                expectedRevision = previous.revision,
                expectedGeneration = previous.sessionGeneration,
                expectedPhase = previous.phase,
                expectedOrigin = previous.serviceOrigin,
                expectedAccountId = previous.accountId,
                expectedOperationId = previous.operationId,
            )
            if (affected != 1) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
        }
    }

    private suspend fun writeJournalTransitioning(
        dao: SyncSessionAuthorityDao,
        previous: RawJournalRow?,
        newRevision: Long,
        newGeneration: Long,
        originText: String,
        accountId: String,
        operationId: String,
    ) {
        if (previous == null) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
        } else {
            val affected = dao.updateAuthority(
                newRevision = newRevision,
                newGeneration = newGeneration,
                newPhase = TRANSITIONING_PHASE,
                newOrigin = originText,
                newAccountId = accountId,
                newOperationId = operationId,
                expectedRevision = previous.revision,
                expectedGeneration = previous.sessionGeneration,
                expectedPhase = previous.phase,
                expectedOrigin = previous.serviceOrigin,
                expectedAccountId = previous.accountId,
                expectedOperationId = previous.operationId,
            )
            if (affected != 1) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
        }
    }

    private suspend fun writeJournalSignedOut(
        dao: SyncSessionAuthorityDao,
        previous: RawJournalRow?,
        newRevision: Long,
        newGeneration: Long,
        retainedOrigin: String?,
        cachedAccountId: String?,
    ) {
        if (previous == null) {
            val rowId = dao.insertAuthority(
                SyncSessionAuthorityEntity(
                    revision = newRevision,
                    sessionGeneration = newGeneration,
                    phase = SIGNED_OUT_PHASE,
                    serviceOrigin = retainedOrigin,
                    accountId = cachedAccountId,
                    operationId = null,
                ),
            )
            if (rowId != SINGLETON_ROW_ID) {
                throw corrupt()
            }
        } else {
            val affected = dao.updateAuthority(
                newRevision = newRevision,
                newGeneration = newGeneration,
                newPhase = SIGNED_OUT_PHASE,
                newOrigin = retainedOrigin,
                newAccountId = cachedAccountId,
                newOperationId = null,
                expectedRevision = previous.revision,
                expectedGeneration = previous.sessionGeneration,
                expectedPhase = previous.phase,
                expectedOrigin = previous.serviceOrigin,
                expectedAccountId = previous.accountId,
                expectedOperationId = previous.operationId,
            )
            if (affected != 1) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
        }
    }

    private suspend fun writeDevice(
        dao: SyncSessionAuthorityDao,
        previous: RawDeviceRow?,
        newActiveAccountId: String?,
        newCachedAccountId: String?,
        newGeneration: Long,
        newCacheState: String,
    ) {
        if (previous == null) {
            val rowId = dao.insertDeviceState(
                SyncDeviceStateEntity(
                    activeAccountId = newActiveAccountId,
                    cachedAccountId = newCachedAccountId,
                    sessionGeneration = newGeneration,
                    cacheState = newCacheState,
                ),
            )
            if (rowId != SINGLETON_ROW_ID) {
                throw corrupt()
            }
        } else {
            val affected = dao.updateDeviceState(
                newActiveAccountId = newActiveAccountId,
                newCachedAccountId = newCachedAccountId,
                newGeneration = newGeneration,
                newCacheState = newCacheState,
                expectedActiveAccountId = previous.activeAccountId,
                expectedCachedAccountId = previous.cachedAccountId,
                expectedGeneration = previous.sessionGeneration,
                expectedCacheState = previous.cacheState,
            )
            if (affected != 1) {
                throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.STALE_AUTHORITY)
            }
        }
    }

    private fun observeLocked(dao: SyncSessionAuthorityDao): AuthorityObservation {
        val journal = readJournal(dao)
        val device = readDevice(dao)
        if (journal == null) {
            if (device == null) {
                return AuthorityObservation(
                    snapshot = RecoveryRoomSnapshot(
                        revision = 0L,
                        sessionGeneration = 0L,
                        cachedAccountId = null,
                        phase = RecoveryRoomPhase.SIGNED_OUT,
                        identity = null,
                        baselineComplete = false,
                    ),
                    journal = null,
                    device = null,
                )
            }
            if (device.activeAccountId != null) {
                throw corrupt()
            }
            readSelectedCache(dao, device.cachedAccountId)
            return AuthorityObservation(
                snapshot = RecoveryRoomSnapshot(
                    revision = 0L,
                    sessionGeneration = device.sessionGeneration,
                    cachedAccountId = device.cachedAccountId,
                    phase = RecoveryRoomPhase.SIGNED_OUT,
                    identity = null,
                    baselineComplete = false,
                ),
                journal = null,
                device = device,
            )
        }
        if (device == null || journal.sessionGeneration != device.sessionGeneration) {
            throw corrupt()
        }
        when (journal.phase) {
            SIGNED_OUT_PHASE -> {
                if (journal.operationId != null || device.activeAccountId != null ||
                    journal.accountId != device.cachedAccountId
                ) {
                    throw corrupt()
                }
                if (journal.serviceOrigin != null && device.cachedAccountId == null) {
                    throw corrupt()
                }
                readSelectedCache(dao, device.cachedAccountId)
                return AuthorityObservation(
                    snapshot = RecoveryRoomSnapshot(
                        revision = journal.revision,
                        sessionGeneration = journal.sessionGeneration,
                        cachedAccountId = device.cachedAccountId,
                        phase = RecoveryRoomPhase.SIGNED_OUT,
                        identity = null,
                        baselineComplete = false,
                    ),
                    journal = journal,
                    device = device,
                )
            }
            PREPARING_PHASE, TRANSITIONING_PHASE -> {
                if (journal.sessionGeneration < 1L || journal.serviceOrigin == null ||
                    journal.accountId == null || journal.operationId == null ||
                    device.cachedAccountId != journal.accountId ||
                    device.activeAccountId != null
                ) {
                    throw corrupt()
                }
                readSelectedCache(dao, journal.accountId)
                val phase = if (journal.phase == PREPARING_PHASE) {
                    RecoveryRoomPhase.PREPARING
                } else {
                    RecoveryRoomPhase.TRANSITIONING
                }
                return AuthorityObservation(
                    snapshot = RecoveryRoomSnapshot(
                        revision = journal.revision,
                        sessionGeneration = journal.sessionGeneration,
                        cachedAccountId = journal.accountId,
                        phase = phase,
                        identity = RecoveryIdentity(
                            binding = ServiceSessionBinding(
                                origin = parseCanonicalOrigin(journal.serviceOrigin),
                                accountId = journal.accountId,
                                sessionGeneration = journal.sessionGeneration,
                            ),
                            operationId = journal.operationId,
                        ),
                        baselineComplete = false,
                    ),
                    journal = journal,
                    device = device,
                )
            }
            ACTIVE_PHASE -> {
                if (journal.sessionGeneration < 1L || journal.serviceOrigin == null ||
                    journal.accountId == null || journal.operationId == null ||
                    device.activeAccountId != journal.accountId ||
                    device.cachedAccountId != journal.accountId
                ) {
                    throw corrupt()
                }
                val baseline = readSelectedCache(dao, journal.accountId)?.baselineComplete ?: false
                return AuthorityObservation(
                    snapshot = RecoveryRoomSnapshot(
                        revision = journal.revision,
                        sessionGeneration = journal.sessionGeneration,
                        cachedAccountId = journal.accountId,
                        phase = RecoveryRoomPhase.ACTIVE,
                        identity = RecoveryIdentity(
                            binding = ServiceSessionBinding(
                                origin = parseCanonicalOrigin(journal.serviceOrigin),
                                accountId = journal.accountId,
                                sessionGeneration = journal.sessionGeneration,
                            ),
                            operationId = journal.operationId,
                        ),
                        baselineComplete = baseline,
                    ),
                    journal = journal,
                    device = device,
                )
            }
            else -> throw corrupt()
        }
    }

    private fun readJournal(dao: SyncSessionAuthorityDao): RawJournalRow? {
        dao.observeAuthorityRows().use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            if (cursor.count != 1) {
                throw corrupt()
            }
            cursor.moveToFirst()
            if (cursor.requireCounter(0) != 1L) {
                throw corrupt()
            }
            val revision = cursor.requireCounter(1)
            if (revision < 1L) {
                throw corrupt()
            }
            val generation = cursor.requireCounter(2)
            if (generation < 0L) {
                throw corrupt()
            }
            val phase = cursor.requireLabel(3)
            if (phase != SIGNED_OUT_PHASE && phase != PREPARING_PHASE &&
                phase != ACTIVE_PHASE && phase != TRANSITIONING_PHASE
            ) {
                throw corrupt()
            }
            val originText = cursor.optionalLabel(4)
            if (originText != null) {
                parseCanonicalOrigin(originText)
            }
            val accountId = cursor.optionalLabel(5)
            if (accountId != null) {
                requireStoredUuid(accountId)
            }
            val operationId = cursor.optionalLabel(6)
            if (operationId != null) {
                requireStoredUuid(operationId)
            }
            return RawJournalRow(
                revision = revision,
                sessionGeneration = generation,
                phase = phase,
                serviceOrigin = originText,
                accountId = accountId,
                operationId = operationId,
            )
        }
    }

    private fun readDevice(dao: SyncSessionAuthorityDao): RawDeviceRow? {
        dao.observeDeviceRows().use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            if (cursor.count != 1) {
                throw corrupt()
            }
            cursor.moveToFirst()
            if (cursor.requireCounter(0) != 1L) {
                throw corrupt()
            }
            val activeAccountId = cursor.optionalLabel(1)
            if (activeAccountId != null) {
                requireStoredUuid(activeAccountId)
            }
            val cachedAccountId = cursor.optionalLabel(2)
            if (cachedAccountId != null) {
                requireStoredUuid(cachedAccountId)
            }
            val generation = cursor.requireCounter(3)
            if (generation < 0L) {
                throw corrupt()
            }
            val cacheState = cursor.requireLabel(4)
            return RawDeviceRow(
                activeAccountId = activeAccountId,
                cachedAccountId = cachedAccountId,
                sessionGeneration = generation,
                cacheState = cacheState,
            )
        }
    }

    private fun readSelectedCache(dao: SyncSessionAuthorityDao, accountId: String?): RawCacheRow? {
        if (accountId == null) {
            return null
        }
        dao.observeAccountCache(accountId).use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            if (cursor.count != 1) {
                throw corrupt()
            }
            cursor.moveToFirst()
            if (cursor.requireLabel(0) != accountId) {
                throw corrupt()
            }
            val baseline = cursor.requireCounter(1)
            if (baseline != 0L && baseline != 1L) {
                throw corrupt()
            }
            val cacheEpoch = cursor.requireCounter(2)
            if (cacheEpoch < 0L) {
                throw corrupt()
            }
            val dirty = cursor.requireCounter(3)
            if (dirty != 0L && dirty != 1L) {
                throw corrupt()
            }
            val materialized = cursor.requireCounter(4)
            if (materialized < 0L || materialized > cacheEpoch) {
                throw corrupt()
            }
            return RawCacheRow(baselineComplete = baseline == 1L)
        }
    }

    private fun Cursor.requireCounter(index: Int): Long {
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) {
            throw corrupt()
        }
        return getLong(index)
    }

    private fun Cursor.requireLabel(index: Int): String {
        if (getType(index) != Cursor.FIELD_TYPE_STRING) {
            throw corrupt()
        }
        return getString(index)
    }

    private fun Cursor.optionalLabel(index: Int): String? {
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_STRING -> getString(index)
            else -> throw corrupt()
        }
    }

    private fun requireStoredUuid(value: String) {
        try {
            requireWorkoutUuid(value, "id")
        } catch (_: Exception) {
            throw corrupt()
        }
    }

    private fun requireUuid(value: String): String {
        try {
            return requireWorkoutUuid(value, "id")
        } catch (_: Exception) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.INVALID_INPUT)
        }
    }

    private fun parseCanonicalOrigin(text: String): ServiceOrigin {
        val parsed = try {
            ServiceOrigin.parse(text)
        } catch (_: Exception) {
            throw corrupt()
        }
        if (canonicalOriginText(parsed) != text) {
            throw corrupt()
        }
        return parsed
    }

    private fun canonicalOriginText(origin: ServiceOrigin): String {
        val host = if (origin.host.contains(':')) "[${origin.host}]" else origin.host
        return "https://$host:${origin.port}"
    }

    private fun checkedNext(value: Long): Long {
        if (value == Long.MAX_VALUE) {
            throw RoomSessionAuthorityException(RoomSessionAuthorityFailure.COUNTER_OVERFLOW)
        }
        return value + 1L
    }

    private fun corrupt(): RoomSessionAuthorityException =
        RoomSessionAuthorityException(RoomSessionAuthorityFailure.CORRUPT_AUTHORITY)

    private data class AuthorityObservation(
        val snapshot: RecoveryRoomSnapshot,
        val journal: RawJournalRow?,
        val device: RawDeviceRow?,
    )

    private data class RawJournalRow(
        val revision: Long,
        val sessionGeneration: Long,
        val phase: String,
        val serviceOrigin: String?,
        val accountId: String?,
        val operationId: String?,
    )

    private data class RawDeviceRow(
        val activeAccountId: String?,
        val cachedAccountId: String?,
        val sessionGeneration: Long,
        val cacheState: String,
    )

    private data class RawCacheRow(val baselineComplete: Boolean)

    /**
     * Strict ACTIVE readiness observation for completion recovery: the exact
     * ACTIVE Room snapshot plus its selected-account cache row (null when
     * absent). Displays are fixed and redacted.
     */
    internal data class ActiveReadyObservation(
        val snapshot: RecoveryRoomSnapshot,
        val cache: SyncAccountCacheEntity?,
    ) {
        override fun toString(): String = "ActiveReadyObservation(redacted)"
    }

    private companion object {
        const val SIGNED_OUT_PHASE = "signed_out"
        const val PREPARING_PHASE = "preparing"
        const val ACTIVE_PHASE = "active"
        const val TRANSITIONING_PHASE = "transitioning"
        const val PREPARING_CACHE_STATE = "preparing"
        const val PRIVATE_CACHE_STATE = "private"
        const val READY_CACHE_STATE = "ready"
        const val SINGLETON_ROW_ID = 1L
    }
}
