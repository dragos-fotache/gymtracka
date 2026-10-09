package com.dragosfotache.gymtracka.data.sync.legacy

import android.database.Cursor
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntryEntity
import com.dragosfotache.gymtracka.data.sync.codec.encodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.session.RecoveryIdentity
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.SyncTransaction
import com.dragosfotache.gymtracka.data.sync.store.readDurableOutboxRow
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/**
 * Inert Room capture/recovery boundary for the R13a legacy import.
 * Explicit caller-owned private work; no public UI/DAO integration.
 * Cancellation has precedence; closed failures carry only their category;
 * arbitrary callback/storage runtime errors map to closed UNAVAILABLE.
 * Stale arises only from actual fence mismatch; constraint/trigger failures
 * are UNAVAILABLE. Lease close is always attempted once and never swallowed
 * when it is the primary outcome; cancellation dominates.
 */
internal class LegacyImportStore(
    private val database: GymDatabase,
    private val barrier: LegacyImportCaptureBarrier,
    private val processCheck: () -> Unit,
    private val newMutationId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun capture(
        expected: RecoveryRoomSnapshot,
        target: LegacyImportTarget,
    ): LegacyImportCaptureResult {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        val lease = acquireBarrier(expected)
        var primary: Throwable? = null
        try {
            val result = try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    checkLease(lease)
                    captureInsideTransaction(expected, target, lease)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
            checkLease(lease)
            return result
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            var closeErr: Throwable? = null
            try {
                closeThrowing(lease)
            } catch (t: Throwable) {
                closeErr = t
            }
            currentCoroutineContext().ensureActive()
            if (closeErr != null) {
                if (primary == null) throw closeErr
                if (closeErr is CancellationException) throw closeErr
                if (primary is CancellationException) throw primary
            }
        }
    }

    suspend fun read(target: LegacyImportTarget): LegacyImportEvidence? {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        try {
            val result = try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    readInsideTransaction(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
            return result
        } catch (t: Throwable) {
            throw t
        }
    }

    /**
     * R13b target-discovering advisory progress read. Returns null when no
     * migration exists; throws closed CORRUPT_EVIDENCE on missing/corrupt
     * evidence. Performs no HTTP, UUID generation, credential changes or
     * cleanup. Must run outside a caller transaction.
     */
    suspend fun observeAny(): LegacyImportProgress? {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        try {
            val result = try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    observeAnyInsideTransaction()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
            return result
        } catch (t: Throwable) {
            throw t
        }
    }

    private suspend fun observeAnyInsideTransaction(): LegacyImportProgress? {
        val journal = readJournalStrict()
        val entries = readEntriesStrict()
        if (journal == null) {
            if (entries.isEmpty()) {
                if (LegacyImportSettlement.readProgressStrict(database) != null ||
                    LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                return null
            }
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val target = try {
            LegacyImportTarget(
                origin = LegacyImportSource.parseCanonicalOrigin(journal.serviceOrigin),
                accountId = requireWorkoutUuid(journal.accountId, "accountId"),
                operationId = requireWorkoutUuid(journal.operationId, "operationId"),
            )
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val recovered = validateRecovered(journal, entries, target)
        if (recovered.phase == LegacyImportPhase.BLOCKED) {
            if (LegacyImportSettlement.readProgressStrict(database) != null ||
                LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyBlockedRecovery(journal, target, recovered)
            return LegacyImportProgress(
                phase = LegacyImportProgressPhase.BLOCKED,
                importCount = 0L,
                acceptedCount = 0L,
                activeReplacementRequired = false,
                activeReplacementConsented = false,
            )
        }
        val evidence = verifySettledRecovery(journal, entries, target)
        val view = LegacyImportSettlement.validateView(database, journal, entries, target, evidence.room)
        val activeRequired = view.activeMutationId != null
        val activeConsented = view.activeMutationId != null &&
            view.progress.activeConsentMutationId == view.activeMutationId
        val phase = if (evidence.phase == LegacyImportPhase.SETTLED) {
            LegacyImportProgressPhase.SETTLED
        } else {
            LegacyImportProgressPhase.CAPTURED
        }
        return LegacyImportProgress(
            phase = phase,
            importCount = journal.importCount,
            acceptedCount = view.acceptedPrefix.toLong(),
            activeReplacementRequired = activeRequired,
            activeReplacementConsented = activeConsented,
        )
    }

    /**
     * R13b durable active-replacement consent. Records the exact original
     * active mutation consent under the unchanged binding; rereads evidence
     * after commit. Consent survives reopening for the same original
     * request. Must run outside a caller transaction.
     */
    suspend fun recordActiveConsent(
        target: LegacyImportTarget,
        mutationId: String,
        expected: RecoveryRoomSnapshot,
    ) {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        try {
            try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    recordActiveConsentInsideTransaction(target, mutationId, expected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
        } catch (t: Throwable) {
            throw t
        }
    }

    /**
     * R13b consent-need probe. Returns the exact original active-import
     * replacement request when the retained migration carries an active
     * import without durable matching consent; null when no migration,
     * blocked, no active import or already consented. Throws closed
     * CORRUPT_EVIDENCE on missing/corrupt evidence. Reads only.
     */
    suspend fun consentRequestForActive(): LegacyImportReplacementRequest? {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        try {
            val result = try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    consentRequestInsideTransaction()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
            return result
        } catch (t: Throwable) {
            throw t
        }
    }

    private suspend fun consentRequestInsideTransaction(): LegacyImportReplacementRequest? {
        val journal = readJournalStrict() ?: return null
        val entries = readEntriesStrict()
        if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
            if (entries.isNotEmpty() ||
                LegacyImportSettlement.readProgressStrict(database) != null ||
                LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            return null
        }
        val target = try {
            LegacyImportTarget(
                origin = LegacyImportSource.parseCanonicalOrigin(journal.serviceOrigin),
                accountId = requireWorkoutUuid(journal.accountId, "accountId"),
                operationId = requireWorkoutUuid(journal.operationId, "operationId"),
            )
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        validateRecovered(journal, entries, target)
        val current = currentAuthorityStrict()
        val view = LegacyImportSettlement.validateView(database, journal, entries, target, current)
        val active = view.activeMutationId ?: return null
        if (view.progress.activeConsentMutationId == active) return null
        if (view.progress.activeConsentMutationId != null) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        return LegacyImportReplacementRequest(target, active)
    }

    /**
     * R13b retained migration target read. Returns the journal target
     * (captured or blocked) or null when no journal exists. Throws closed
     * CORRUPT_EVIDENCE on malformed journal evidence. Reads only.
     */
    suspend fun retainedTarget(): LegacyImportTarget? {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        try {
            val result = try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    val journal = readJournalStrict() ?: return@withTransaction null
                    try {
                        LegacyImportTarget(
                            origin = LegacyImportSource.parseCanonicalOrigin(journal.serviceOrigin),
                            accountId = requireWorkoutUuid(journal.accountId, "accountId"),
                            operationId = requireWorkoutUuid(journal.operationId, "operationId"),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
            return result
        } catch (t: Throwable) {
            throw t
        }
    }

    /**
     * R13b reauth evidence read. Returns the strictly validated retained
     * captured/settled evidence with current lineage for private-import
     * reauthentication. Throws on absent/blocked/corrupt evidence.
     */
    suspend fun readForReauth(): LegacyImportEvidence {
        currentCoroutineContext().ensureActive()
        runProcess()
        if (database.inTransaction()) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        try {
            val result = try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    runProcess()
                    val journal = readJournalStrict()
                        ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    val entries = readEntriesStrict()
                    if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    val target = try {
                        LegacyImportTarget(
                            origin = LegacyImportSource.parseCanonicalOrigin(journal.serviceOrigin),
                            accountId = requireWorkoutUuid(journal.accountId, "accountId"),
                            operationId = requireWorkoutUuid(journal.operationId, "operationId"),
                        )
                    } catch (_: Exception) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    validateRecovered(journal, entries, target)
                    verifySettledRecovery(journal, entries, target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            runProcess()
            return result
        } catch (t: Throwable) {
            throw t
        }
    }

    private suspend fun recordActiveConsentInsideTransaction(
        target: LegacyImportTarget,
        mutationId: String,
        expected: RecoveryRoomSnapshot,
    ) {
        try {
            requireWorkoutUuid(mutationId, "mutationId")
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        }
        val current = try {
            RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (current != expected) {
            throw LegacyImportException(LegacyImportFailure.STALE_AUTHORITY)
        }
        val journal = readJournalStrict()
            ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        val entries = readEntriesStrict()
        validateRecovered(journal, entries, target)
        val view = LegacyImportSettlement.validateView(database, journal, entries, target, current)
        val active = view.activeMutationId
            ?: throw LegacyImportException(LegacyImportFailure.INVALID_INPUT)
        if (active != mutationId) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        val stored = view.progress.activeConsentMutationId
        if (stored != null && stored != mutationId) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (stored == mutationId) {
            verifySettledRecovery(journal, entries, target)
            return
        }
        try {
            val db = database.openHelper.writableDatabase
            val values = android.content.ContentValues().apply {
                put("active_consent_mutation_id", mutationId)
            }
            val expectedOp = expected.identity?.operationId
            val affected = db.update(
                "sync_legacy_import_progress",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                values,
                "id = 1 AND room_revision = ? AND room_session_generation = ? AND room_phase = ? AND " +
                    (if (expectedOp == null) "operation_id IS NULL" else "operation_id = ?") +
                    " AND active_consent_mutation_id IS NULL",
                if (expectedOp == null) {
                    arrayOf(
                        expected.revision.toString(),
                        expected.sessionGeneration.toString(),
                        anchorPhaseName(expected.phase),
                    )
                } else {
                    arrayOf(
                        expected.revision.toString(),
                        expected.sessionGeneration.toString(),
                        anchorPhaseName(expected.phase),
                        expectedOp,
                    )
                },
            )
            if (affected != 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        runProcess()
        val reread = try {
            RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (reread != expected) {
            throw LegacyImportException(LegacyImportFailure.STALE_AUTHORITY)
        }
        val progress = LegacyImportSettlement.readProgressStrict(database)
            ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (progress.roomRevision != expected.revision ||
            progress.roomSessionGeneration != expected.sessionGeneration ||
            progress.roomPhase != anchorPhaseName(expected.phase) ||
            progress.operationId != expected.identity?.operationId ||
            progress.activeConsentMutationId != mutationId
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        verifySettledRecovery(journal, entries, target)
    }

    private fun anchorPhaseName(phase: RecoveryRoomPhase): String =
        when (phase) {
            RecoveryRoomPhase.SIGNED_OUT -> "signed_out"
            RecoveryRoomPhase.PREPARING -> "preparing"
            RecoveryRoomPhase.TRANSITIONING -> "transitioning"
            RecoveryRoomPhase.ACTIVE -> "active"
        }

    private suspend fun currentAuthorityStrict(): RecoveryRoomSnapshot {
        try {
            currentCoroutineContext().ensureActive()
            return RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private data class Recovered(
        val phase: LegacyImportPhase,
        val room: RecoveryRoomSnapshot,
        val importCount: Long,
        val errorCode: String?,
    )

    private fun validateRecovered(
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
    ): Recovered {
        val storedTarget = try {
            LegacyImportTarget(
                origin = LegacyImportSource.parseCanonicalOrigin(journal.serviceOrigin),
                accountId = requireWorkoutUuid(journal.accountId, "accountId"),
                operationId = requireWorkoutUuid(journal.operationId, "operationId"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (storedTarget.origin != target.origin ||
            storedTarget.accountId != target.accountId ||
            storedTarget.operationId != target.operationId
        ) {
            throw LegacyImportException(LegacyImportFailure.TARGET_MISMATCH)
        }
        if (journal.evidenceVersion != LegacyImportSource.EVIDENCE_VERSION) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (journal.sourceRevision < 0L || journal.sourceSessionGeneration < 0L ||
            journal.roomRevision < 0L || journal.roomSessionGeneration < 0L ||
            journal.importCount < 0L
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (journal.sourceRevision == Long.MAX_VALUE || journal.sourceSessionGeneration == Long.MAX_VALUE) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (journal.roomRevision != journal.sourceRevision + 1L ||
            journal.roomSessionGeneration != journal.sourceSessionGeneration + 1L
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        try {
            LegacyImportSource.validateSourceJson(journal.sourceJson)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val phase = when (journal.phase) {
            LegacyImportSource.PHASE_CAPTURED -> LegacyImportPhase.CAPTURED
            LegacyImportSource.PHASE_BLOCKED -> LegacyImportPhase.BLOCKED
            else -> throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (phase == LegacyImportPhase.BLOCKED) {
            if (journal.importCount != 0L ||
                journal.errorCode != LegacyImportSource.BLOCKED_ERROR_CODE ||
                entries.isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val room = RecoveryRoomSnapshot(
                revision = journal.roomRevision,
                sessionGeneration = journal.roomSessionGeneration,
                cachedAccountId = null,
                phase = RecoveryRoomPhase.SIGNED_OUT,
                identity = null,
                baselineComplete = false,
            )
            return Recovered(phase, room, 0L, journal.errorCode)
        }
        if (journal.errorCode != null) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (journal.importCount != entries.size.toLong() || entries.isEmpty()) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        entries.forEachIndexed { index, e ->
            if (e.ordinal != index.toLong()) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            try {
                AggregateType.fromWire(e.aggregateType)
                requireWorkoutUuid(e.aggregateId, "aggregateId")
                requireWorkoutUuid(e.mutationId, "mutationId")
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (e.sequence <= 0L || e.localGeneration < 1L ||
                e.payloadVersion != DURABLE_PAYLOAD_VERSION.toLong()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            try {
                JSONObject(e.payloadJson)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (index > 0 && e.sequence <= entries[index - 1].sequence) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
        if (entries.map { it.mutationId }.toSet().size != entries.size ||
            entries.map { it.sequence }.toSet().size != entries.size
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val expected = try {
            LegacyImportSource.expectedEntriesFromSource(JSONObject(journal.sourceJson))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (expected.size != entries.size) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        expected.forEachIndexed { index, (type, id, originalGen) ->
            val e = entries[index]
            if (e.aggregateType != type.wireValue || e.aggregateId != id) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (originalGen == Long.MAX_VALUE || e.localGeneration != originalGen + 1L) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
        val room = try {
            RecoveryRoomSnapshot(
                revision = journal.roomRevision,
                sessionGeneration = journal.roomSessionGeneration,
                cachedAccountId = journal.accountId,
                phase = RecoveryRoomPhase.PREPARING,
                identity = RecoveryIdentity(
                    binding = ServiceSessionBinding(
                        origin = storedTarget.origin,
                        accountId = storedTarget.accountId,
                        sessionGeneration = journal.roomSessionGeneration,
                    ),
                    operationId = storedTarget.operationId,
                ),
                baselineComplete = false,
            )
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        return Recovered(phase, room, journal.importCount, null)
    }

    private suspend fun captureInsideTransaction(
        expected: RecoveryRoomSnapshot,
        target: LegacyImportTarget,
        lease: ProjectionPublicationLease,
    ): LegacyImportCaptureResult {
        val authority = RoomSessionAuthority(database)
        val observed = try {
            authority.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (observed != expected) throw LegacyImportException(LegacyImportFailure.STALE_AUTHORITY)
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)

        val journal = readJournalStrict()
        val entries = readEntriesStrict()
        if (journal != null) {
            val recovered = validateRecovered(journal, entries, target)
            if (recovered.phase == LegacyImportPhase.BLOCKED) {
                if (recovered.room != expected) {
                    throw LegacyImportException(LegacyImportFailure.STALE_AUTHORITY)
                }
                if (LegacyImportSettlement.readProgressStrict(database) != null ||
                    LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                verifyBlockedRecovery(journal, target, recovered)
                return LegacyImportCaptureResult.Retained(
                    LegacyImportEvidence(target, recovered.phase, recovered.room, journal.sourceJson, 0L, journal.errorCode),
                )
            }
            val current = try {
                RoomSessionAuthority(database).read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomSessionAuthorityException) {
                throw mapAuthority(e)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            if (current != expected) {
                throw LegacyImportException(LegacyImportFailure.STALE_AUTHORITY)
            }
            return LegacyImportCaptureResult.Retained(verifySettledRecovery(journal, entries, target))
        }
        if (entries.isNotEmpty()) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        requireNoProgressOrReceipts()

        if (observed.phase != RecoveryRoomPhase.SIGNED_OUT ||
            observed.cachedAccountId != null ||
            observed.identity != null ||
            observed.baselineComplete
        ) {
            throw LegacyImportException(LegacyImportFailure.FOREIGN_STORAGE)
        }
        val legacyDao = database.syncLegacyImportDao()
        try {
            if (legacyDao.hasAccountWorkoutRoot() || legacyDao.hasAccountRoutineRoot() ||
                legacyDao.hasAccountCustomRoot() || legacyDao.hasAccountAnyExerciseRoot() ||
                legacyDao.hasAnyOutbox() || legacyDao.hasAnySyncState() ||
                legacyDao.hasAnyAccountCache() || legacyDao.hasAnyCanonicalRoots() ||
                legacyDao.hasAnySnapshotStage() || legacyDao.hasAnySnapshotRecord() ||
                legacyDao.hasAnySnapshotToken()
            ) {
                throw LegacyImportException(LegacyImportFailure.FOREIGN_STORAGE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)

        val sourceJson = try {
            LegacyImportSource.freeze(database)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        try {
            LegacyImportSource.validateSourceJson(sourceJson)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncModelException) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }

        val graph = try {
            LegacyImportSource.buildValidatedGraph(database)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: LegacyImportSource.EmptyLocalData) {
            currentCoroutineContext().ensureActive()
            runProcess()
            checkLease(lease)
            verifyNoWritesAfterEmpty()
            return LegacyImportCaptureResult.NoLocalData
        } catch (_: SyncModelException) {
            return commitBlocked(authority, legacyDao, expected, target, sourceJson, lease)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }

        if (graph.importCount <= 0) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (observed.revision == Long.MAX_VALUE || observed.sessionGeneration == Long.MAX_VALUE) {
            throw LegacyImportException(LegacyImportFailure.COUNTER_OVERFLOW)
        }
        val originalGenerations = readLocalGenerationsStrict()
        for (gen in originalGenerations.values) {
            if (gen == Long.MAX_VALUE) throw LegacyImportException(LegacyImportFailure.COUNTER_OVERFLOW)
        }
        val highWater = readOutboxHighWater()
        try {
            Math.addExact(highWater, graph.importCount.toLong())
        } catch (_: ArithmeticException) {
            throw LegacyImportException(LegacyImportFailure.COUNTER_OVERFLOW)
        }
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        return commitCaptured(authority, legacyDao, expected, target, sourceJson, graph, originalGenerations, lease)
    }

    private suspend fun commitBlocked(
        authority: RoomSessionAuthority,
        legacyDao: com.dragosfotache.gymtracka.data.gym.SyncLegacyImportDao,
        expected: RecoveryRoomSnapshot,
        target: LegacyImportTarget,
        sourceJson: String,
        lease: ProjectionPublicationLease,
    ): LegacyImportCaptureResult {
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        val fenced = try {
            authority.fencePrivate(expected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (fenced.phase != RecoveryRoomPhase.SIGNED_OUT ||
            fenced.cachedAccountId != null || fenced.identity != null ||
            fenced.baselineComplete
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val originText = LegacyImportSource.canonicalOriginText(target.origin)
        val journal = SyncLegacyImportEntity(
            evidenceVersion = LegacyImportSource.EVIDENCE_VERSION,
            serviceOrigin = originText,
            accountId = target.accountId,
            operationId = target.operationId,
            sourceRevision = expected.revision,
            sourceSessionGeneration = expected.sessionGeneration,
            roomRevision = fenced.revision,
            roomSessionGeneration = fenced.sessionGeneration,
            phase = LegacyImportSource.PHASE_BLOCKED,
            sourceJson = sourceJson,
            importCount = 0L,
            errorCode = LegacyImportSource.BLOCKED_ERROR_CODE,
        )
        try {
            legacyDao.insertJournal(journal)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        val reread = readJournalStrict()
            ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (reread != journal) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (readEntriesStrict().isNotEmpty()) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        requireNoProgressOrReceipts()
        val refreeze = try {
            LegacyImportSource.freeze(database)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (refreeze != sourceJson) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        try {
            if (legacyDao.hasAnyOutbox() || legacyDao.hasAccountWorkoutRoot() ||
                legacyDao.hasAccountRoutineRoot() || legacyDao.hasAccountCustomRoot() ||
                legacyDao.hasAccountAnyExerciseRoot() || legacyDao.hasAnyAccountCache() ||
                legacyDao.hasAnyCanonicalRoots() || legacyDao.hasAnySyncState() ||
                legacyDao.hasAnySnapshotStage() || legacyDao.hasAnySnapshotRecord() ||
                legacyDao.hasAnySnapshotToken()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        val rereadAuthority = try {
            authority.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (rereadAuthority != fenced) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        return LegacyImportCaptureResult.Retained(
            LegacyImportEvidence(target, LegacyImportPhase.BLOCKED, fenced, sourceJson, 0L, journal.errorCode),
        )
    }

    private suspend fun commitCaptured(
        authority: RoomSessionAuthority,
        legacyDao: com.dragosfotache.gymtracka.data.gym.SyncLegacyImportDao,
        expected: RecoveryRoomSnapshot,
        target: LegacyImportTarget,
        sourceJson: String,
        graph: LegacyImportSource.ValidGraph,
        originalGenerations: Map<Pair<AggregateType, String>, Long>,
        lease: ProjectionPublicationLease,
    ): LegacyImportCaptureResult {
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        val ordered = graph.orderedRecords()
        for ((type, record) in ordered) {
            val affected = try {
                when (type) {
                    AggregateType.CUSTOM_EXERCISE ->
                        legacyDao.associateCustomAccount(record.aggregateId, target.accountId)
                    AggregateType.ROUTINE ->
                        legacyDao.associateRoutineAccount(record.aggregateId, target.accountId)
                    AggregateType.WORKOUT ->
                        legacyDao.associateWorkoutAccount(record.aggregateId, target.accountId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            if (affected != 1) throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            verifyRootMetadataStrict(type, record.aggregateId, target.accountId, null, null)
            currentCoroutineContext().ensureActive()
            runProcess()
            checkLease(lease)
        }
        try {
            val authorityDao = database.syncSessionAuthorityDao()
            if (authorityDao.hasLocalOnlyWorkoutRoot() || authorityDao.hasLocalOnlyRoutineRoot() ||
                authorityDao.hasLocalOnlyCustomExerciseRoot()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }

        if (!database.inTransaction()) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        val tx = SyncTransaction(database, target.accountId, newMutationId)
        val captured = ArrayList<Triple<AggregateType, String, com.dragosfotache.gymtracka.data.sync.store.CaptureOutcome>>()
        val payloads = HashMap<String, String>()
        try {
            for ((type, record) in ordered) {
                currentCoroutineContext().ensureActive()
                runProcess()
                checkLease(lease)
                val payload = MutationPayload.AggregateImport(record)
                val outcome = tx.captureEdit(
                    rootType = type,
                    rootId = record.aggregateId,
                    kind = MutationKind.AGGREGATE_IMPORT,
                    changed = true,
                    extraRoots = emptyList(),
                    newPayload = { payload },
                ) ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                val request = outcome.request
                    ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                val original = originalGenerations[type to record.aggregateId]
                    ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                if (original == Long.MAX_VALUE) throw LegacyImportException(LegacyImportFailure.COUNTER_OVERFLOW)
                if (request.envelope.localGeneration != original + 1L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                captured.add(Triple(type, record.aggregateId, outcome))
                payloads[request.envelope.mutationId] =
                    encodeDurableRequest(request).toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (e: SyncModelException) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        } finally {
            try {
                tx.close()
            } catch (_: Exception) {
            }
        }
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)

        val mutationIds = captured.map { it.third.request!!.envelope.mutationId }
        if (mutationIds.toSet().size != mutationIds.size) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val sequences = ArrayList<Long>()
        for ((type, aggregateId, outcome) in captured) {
            val request = outcome.request
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            verifyOutboxRowStrict(
                type = type,
                aggregateId = aggregateId,
                mutationId = request.envelope.mutationId,
                localGeneration = request.envelope.localGeneration,
                intendedPayload = payloads[request.envelope.mutationId]
                    ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE),
                accountId = target.accountId,
            )
            val storedSequence = readOutboxSequenceStrict(request.envelope.mutationId, target.accountId)
            sequences.add(storedSequence)
            verifyRootMetadataStrict(type, aggregateId, target.accountId, "pending", request.envelope.localGeneration)
            verifyLedgerStrict(type, aggregateId, target.accountId, request.envelope.localGeneration)
        }
        if (sequences.toSet().size != sequences.size) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (sequences.sorted() != sequences) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        verifyAccountCacheStrict(target.accountId)

        val prepared = try {
            authority.beginPreparation(expected, target.origin, target.accountId, target.operationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (prepared.phase != RecoveryRoomPhase.PREPARING ||
            prepared.cachedAccountId != target.accountId ||
            prepared.identity == null ||
            prepared.identity.binding.accountId != target.accountId ||
            prepared.identity.binding.origin != target.origin ||
            prepared.identity.operationId != target.operationId ||
            prepared.baselineComplete
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val originText = LegacyImportSource.canonicalOriginText(target.origin)
        val journal = SyncLegacyImportEntity(
            evidenceVersion = LegacyImportSource.EVIDENCE_VERSION,
            serviceOrigin = originText,
            accountId = target.accountId,
            operationId = target.operationId,
            sourceRevision = expected.revision,
            sourceSessionGeneration = expected.sessionGeneration,
            roomRevision = prepared.revision,
            roomSessionGeneration = prepared.sessionGeneration,
            phase = LegacyImportSource.PHASE_CAPTURED,
            sourceJson = sourceJson,
            importCount = ordered.size.toLong(),
            errorCode = null,
        )
        try {
            legacyDao.insertJournal(journal)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        val entryRows = captured.mapIndexed { index, (type, aggregateId, outcome) ->
            val request = outcome.request
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val storedSequence = readOutboxSequenceStrict(request.envelope.mutationId, target.accountId)
            SyncLegacyImportEntryEntity(
                ordinal = index.toLong(),
                aggregateType = type.wireValue,
                aggregateId = aggregateId,
                mutationId = request.envelope.mutationId,
                sequence = storedSequence,
                localGeneration = request.envelope.localGeneration,
                payloadVersion = DURABLE_PAYLOAD_VERSION.toLong(),
                payloadJson = payloads[request.envelope.mutationId]
                    ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE),
            )
        }
        try {
            legacyDao.insertEntries(entryRows)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        try {
            legacyDao.insertProgress(
                com.dragosfotache.gymtracka.data.gym.SyncLegacyImportProgressEntity(
                    roomRevision = prepared.revision,
                    roomSessionGeneration = prepared.sessionGeneration,
                    roomPhase = "preparing",
                    operationId = target.operationId,
                    activeConsentMutationId = null,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        verifyFinalCapturedState(journal, entryRows, target, originalGenerations, prepared)
        currentCoroutineContext().ensureActive()
        runProcess()
        checkLease(lease)
        return LegacyImportCaptureResult.Retained(
            LegacyImportEvidence(
                target, LegacyImportPhase.CAPTURED, prepared,
                sourceJson, ordered.size.toLong(), null,
            ),
        )
    }

    private suspend fun verifyFinalCapturedState(
        journal: SyncLegacyImportEntity,
        entryRows: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
        originalGenerations: Map<Pair<AggregateType, String>, Long>,
        prepared: RecoveryRoomSnapshot,
    ) {
        val rereadJournal = readJournalStrict()
            ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (rereadJournal != journal) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        val rereadEntries = readEntriesStrict()
        if (rereadEntries.size != entryRows.size) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        rereadEntries.forEachIndexed { index, row ->
            if (row != entryRows[index]) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        for (e in entryRows) {
            val type = try {
                AggregateType.fromWire(e.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val original = originalGenerations[type to e.aggregateId]
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (e.localGeneration != original + 1L) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyOutboxRowStrict(
                type = type,
                aggregateId = e.aggregateId,
                mutationId = e.mutationId,
                localGeneration = e.localGeneration,
                intendedPayload = e.payloadJson,
                accountId = target.accountId,
            )
            verifyRootMetadataStrict(type, e.aggregateId, target.accountId, "pending", e.localGeneration)
            verifyLedgerStrict(type, e.aggregateId, target.accountId, e.localGeneration)
        }
        verifyAccountCacheStrict(target.accountId)
        try {
            val targetJson = LegacyImportSource.freezeTargetScope(database, target.accountId)
            LegacyImportSource.verifyBusinessPreserved(journal.sourceJson, targetJson, target.accountId)
            LegacyImportSource.verifyLocalScopeEmpty(database)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: SyncModelException) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        val rereadAuthority = try {
            RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (rereadAuthority != prepared) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        val progress = LegacyImportSettlement.readProgressStrict(database)
            ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (progress.roomRevision != prepared.revision ||
            progress.roomSessionGeneration != prepared.sessionGeneration ||
            progress.roomPhase != "preparing" ||
            progress.operationId != target.operationId ||
            progress.activeConsentMutationId != null
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private suspend fun readInsideTransaction(target: LegacyImportTarget): LegacyImportEvidence? {
        val journal = readJournalStrict()
        val entries = readEntriesStrict()
        if (journal == null) {
            if (entries.isEmpty()) {
                if (LegacyImportSettlement.readProgressStrict(database) != null ||
                    LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                return null
            }
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val recovered = validateRecovered(journal, entries, target)
        if (recovered.phase == LegacyImportPhase.BLOCKED) {
            if (LegacyImportSettlement.readProgressStrict(database) != null ||
                LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyBlockedRecovery(journal, target, recovered)
            return LegacyImportEvidence(target, recovered.phase, recovered.room, journal.sourceJson, 0L, journal.errorCode)
        }
        return verifySettledRecovery(journal, entries, target)
    }

    /**
     * R13b progress-aware captured recovery. Validates the strict
     * settlement view (progress anchor, consecutive accepted prefix,
     * outbox correspondence, frozen source/entries/receipts) and returns
     * evidence with the freshly verified current lineage tuple and
     * definitive accepted count. SETTLED derives only from a complete
     * valid receipt prefix. Partial settlement retains original business
     * checks; complete settlement skips post-publication live-graph
     * comparison while keeping frozen evidence immutable.
     */
    private suspend fun verifySettledRecovery(
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
    ): LegacyImportEvidence {
        val current = try {
            RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        val view = LegacyImportSettlement.validateView(database, journal, entries, target, current)
        if (current.cachedAccountId != target.accountId) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (view.acceptedPrefix < entries.size) {
            verifySettledRootEffects(entries, view, target)
            verifyRetainedSuffix(entries, view, target)
            verifyBusinessValuesWithAckMetadata(journal, entries, view, target)
            try {
                LegacyImportSource.verifyLocalScopeEmpty(database)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyImportException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            verifyAccountCacheStrict(target.accountId)
        }
        val phase = if (view.acceptedPrefix == entries.size) LegacyImportPhase.SETTLED else LegacyImportPhase.CAPTURED
        return LegacyImportEvidence(
            target, phase, current, journal.sourceJson, journal.importCount, null,
            acceptedCount = view.acceptedPrefix.toLong(),
        )
    }

    /**
     * Settled-prefix verification from immutable evidence only: entry
     * wrappers decode from retained bytes (no outbox needed), receipt
     * batches already validated, and each accepted root's acknowledgment
     * metadata/ledger is verified against its receipt revision. Never
     * requires retained outbox rows for the settled prefix.
     */
    private suspend fun verifySettledRootEffects(
        entries: List<SyncLegacyImportEntryEntity>,
        view: LegacyImportSettlement.View,
        target: LegacyImportTarget,
    ) {
        for (i in 0 until view.acceptedPrefix) {
            val e = entries[i]
            val type = try {
                AggregateType.fromWire(e.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val revision = receiptRevisionFor(view, e, type)
            verifySyncedRootMetadataStrict(type, e.aggregateId, target.accountId, e.localGeneration, revision)
            verifySettledLedgerStrict(type, e.aggregateId, target.accountId, e.localGeneration, revision)
        }
    }

    private fun receiptRevisionFor(
        view: LegacyImportSettlement.View,
        e: SyncLegacyImportEntryEntity,
        type: AggregateType,
    ): Long {
        try {
            val batch = view.receiptBatches[e.ordinal.toInt()]
            val change = batch.changes.firstOrNull {
                it.aggregateType == type && it.aggregateId == e.aggregateId
            } ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (change.revision <= 0L) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            return change.revision
        } catch (e2: CancellationException) {
            throw e2
        } catch (e2: LegacyImportException) {
            throw e2
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private suspend fun verifySyncedRootMetadataStrict(
        type: AggregateType,
        id: String,
        accountId: String,
        expectedGeneration: Long,
        expectedRevision: Long,
    ) {
        try {
            val table = when (type) {
                AggregateType.WORKOUT -> "workouts"
                AggregateType.ROUTINE -> "routines"
                AggregateType.CUSTOM_EXERCISE -> "exercises"
            }
            database.openHelper.writableDatabase.query(
                "SELECT account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM $table WHERE id = ?",
                arrayOf<Any>(id),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(0) != Cursor.FIELD_TYPE_STRING || c.getString(0) != accountId) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(1) != Cursor.FIELD_TYPE_INTEGER || c.getLong(1) != expectedRevision) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(2) != Cursor.FIELD_TYPE_INTEGER || c.getLong(2) != expectedGeneration) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(3) != Cursor.FIELD_TYPE_STRING || c.getString(3) != "synced") {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(4) != Cursor.FIELD_TYPE_NULL) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun verifySettledLedgerStrict(
        type: AggregateType,
        id: String,
        accountId: String,
        generation: Long,
        revision: Long,
    ) {
        try {
            database.openHelper.writableDatabase.query(
                """SELECT server_revision, canonical_state, canonical_json,
                    local_generation_watermark, cache_epoch FROM sync_canonical_roots
                    WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?""",
                arrayOf<Any>(accountId, type.wireValue, id),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) != revision) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(1) != Cursor.FIELD_TYPE_STRING || c.getString(1) != "upsert") {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(2) != Cursor.FIELD_TYPE_STRING) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(3) != Cursor.FIELD_TYPE_INTEGER || c.getLong(3) != generation) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(4) != Cursor.FIELD_TYPE_INTEGER || c.getLong(4) != 0L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    /**
     * Retained-suffix verification: exact original outbox rows with full
     * envelope/affected-root checks, pending metadata and strict ledger.
     * Only the unsettled suffix requires retained requests.
     */
    private suspend fun verifyRetainedSuffix(
        entries: List<SyncLegacyImportEntryEntity>,
        view: LegacyImportSettlement.View,
        target: LegacyImportTarget,
    ) {
        for (i in view.acceptedPrefix until entries.size) {
            val e = entries[i]
            val type = try {
                AggregateType.fromWire(e.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyOutboxRowStrict(
                type = type,
                aggregateId = e.aggregateId,
                mutationId = e.mutationId,
                localGeneration = e.localGeneration,
                intendedPayload = e.payloadJson,
                accountId = target.accountId,
            )
            try {
                val decoded = readDurableOutboxRowStrict(e.mutationId, target.accountId)
                if (decoded.envelope.kind != MutationKind.AGGREGATE_IMPORT) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (decoded.envelope.aggregateType != type ||
                    decoded.envelope.aggregateId != e.aggregateId ||
                    decoded.envelope.localGeneration != e.localGeneration
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (decoded.affectedRoots.size != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val effect = decoded.affectedRoots[0]
                if (effect.aggregateType != type || effect.aggregateId != e.aggregateId ||
                    effect.localGeneration != e.localGeneration
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: LegacyImportException) {
                throw e2
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyRootMetadataStrict(type, e.aggregateId, target.accountId, "pending", e.localGeneration)
            verifyLedgerStrict(type, e.aggregateId, target.accountId, e.localGeneration)
        }
    }

    /**
     * Business-values preservation with acknowledgment metadata: every
     * business cell equals the frozen source exactly and no root is
     * missing; per-root metadata follows settlement (synced + receipt
     * revision for accepted roots, pending + null revision for the
     * retained suffix). Legitimate canonical/metadata progress is
     * required, source edits are refused.
     */
    private suspend fun verifyBusinessValuesWithAckMetadata(
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
        view: LegacyImportSettlement.View,
        target: LegacyImportTarget,
    ) {
        try {
            currentCoroutineContext().ensureActive()
            val original = try {
                LegacyImportSource.validateSourceJson(journal.sourceJson)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val live = try {
                LegacyImportSource.validateSourceJson(
                    LegacyImportSource.freezeTargetScope(database, target.accountId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
            }
            val byId = HashMap<Pair<AggregateType, String>, SyncLegacyImportEntryEntity>(entries.size)
            for (e in entries) {
                val type = try {
                    AggregateType.fromWire(e.aggregateType)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (byId.put(type to e.aggregateId, e) != null) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
            val revisionById = HashMap<Pair<AggregateType, String>, Long>(view.acceptedPrefix)
            for (i in 0 until view.acceptedPrefix) {
                val e = entries[i]
                val type = try {
                    AggregateType.fromWire(e.aggregateType)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                revisionById[type to e.aggregateId] = receiptRevisionFor(view, e, type)
            }
            compareRootTable(original, live, "workouts", AggregateType.WORKOUT, 5, 6, 7, 8, byId, revisionById, target)
            compareRootTable(original, live, "routines", AggregateType.ROUTINE, 3, 4, 5, 6, byId, revisionById, target)
            compareRootTable(original, live, "exercises", AggregateType.CUSTOM_EXERCISE, 9, 10, 11, 12, byId, revisionById, target)
            for (key in listOf("workout_exercises", "workout_sets", "routine_exercises", "routine_sets", "exercise_muscle_groups", "exercise_equipment")) {
                compareChildTable(original, live, key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun compareRootTable(
        original: JSONObject,
        live: JSONObject,
        key: String,
        family: AggregateType,
        accountIndex: Int,
        revisionIndex: Int,
        generationIndex: Int,
        stateIndex: Int,
        byId: Map<Pair<AggregateType, String>, SyncLegacyImportEntryEntity>,
        revisionById: Map<Pair<AggregateType, String>, Long>,
        target: LegacyImportTarget,
    ) {
        val oRows = try {
            original.getJSONArray(key)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val tRows = try {
            live.getJSONArray(key)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (oRows.length() != tRows.length()) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        for (i in 0 until oRows.length()) {
            val oRow = try {
                oRows.getJSONArray(i)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val tRow = try {
                tRows.getJSONArray(i)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (oRow.length() != tRow.length()) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val id = cellText(oRow, 0)
            if (cellText(tRow, 0) != id) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val entry = byId[family to id]
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val settled = revisionById.containsKey(family to id)
            for (c in 0 until oRow.length()) {
                when (c) {
                    accountIndex -> {
                        if (cellType(tRow, c) != "text" || cellText(tRow, c) != target.accountId) {
                            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        }
                    }
                    revisionIndex -> {
                        if (settled) {
                            if (cellType(tRow, c) != "int" ||
                                cellLong(tRow, c) != revisionById[family to id]
                            ) {
                                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                            }
                        } else {
                            if (cellType(tRow, c) != "null") {
                                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                            }
                        }
                    }
                    generationIndex -> {
                        if (cellType(tRow, c) != "int" || cellLong(tRow, c) != entry.localGeneration) {
                            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        }
                    }
                    stateIndex -> {
                        val want = if (settled) "synced" else "pending"
                        if (cellType(tRow, c) != "text" || cellText(tRow, c) != want) {
                            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        }
                    }
                    else -> {
                        if (!cellsEqual(oRow.getJSONObject(c), tRow.getJSONObject(c))) {
                            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        }
                    }
                }
            }
        }
    }

    private fun compareChildTable(original: JSONObject, live: JSONObject, key: String) {
        val oRows = try {
            original.getJSONArray(key)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val tRows = try {
            live.getJSONArray(key)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (oRows.length() != tRows.length()) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        for (i in 0 until oRows.length()) {
            val oRow = try {
                oRows.getJSONArray(i)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val tRow = try {
                tRows.getJSONArray(i)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (oRow.length() != tRow.length()) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            for (c in 0 until oRow.length()) {
                if (!cellsEqual(oRow.getJSONObject(c), tRow.getJSONObject(c))) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        }
    }

    private fun cellType(row: org.json.JSONArray, index: Int): String {
        try {
            return row.getJSONObject(index).getString("t")
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellText(row: org.json.JSONArray, index: Int): String {
        try {
            val cell = row.getJSONObject(index)
            if (cell.getString("t") != "text") throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            return cell.getString("v")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellLong(row: org.json.JSONArray, index: Int): Long {
        try {
            val cell = row.getJSONObject(index)
            if (cell.getString("t") != "int") throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            return cell.getString("v").toLong()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellsEqual(a: JSONObject, b: JSONObject): Boolean {
        try {
            if (a.getString("t") != b.getString("t")) return false
            if (a.getString("t") == "null") return true
            return a.getString("v") == b.getString("v")
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private suspend fun verifyCapturedRecovery(
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
        recovered: Recovered,
    ) {
        verifyCapturedEntries(entries, target)
        for (e in entries) {
            val type = try {
                AggregateType.fromWire(e.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyRootMetadataStrict(type, e.aggregateId, target.accountId, "pending", e.localGeneration)
            verifyLedgerStrict(type, e.aggregateId, target.accountId, e.localGeneration)
        }
        verifyAccountCacheStrict(target.accountId)
        try {
            val targetJson = LegacyImportSource.freezeTargetScope(database, target.accountId)
            LegacyImportSource.verifyBusinessPreserved(journal.sourceJson, targetJson, target.accountId)
            LegacyImportSource.verifyLocalScopeEmpty(database)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: SyncModelException) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        verifyAuthorityMatches(recovered.room)
    }

    private suspend fun verifyBlockedRecovery(
        journal: SyncLegacyImportEntity,
        target: LegacyImportTarget,
        recovered: Recovered,
    ) {
        try {
            val refreeze = LegacyImportSource.freeze(database)
            if (refreeze != journal.sourceJson) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val dao = database.syncLegacyImportDao()
            if (dao.hasAnyOutbox() || dao.hasAccountWorkoutRoot() ||
                dao.hasAccountRoutineRoot() || dao.hasAccountCustomRoot() ||
                dao.hasAccountAnyExerciseRoot() || dao.hasAnyAccountCache() ||
                dao.hasAnyCanonicalRoots() || dao.hasAnySyncState() ||
                dao.hasAnySnapshotStage() || dao.hasAnySnapshotRecord() ||
                dao.hasAnySnapshotToken()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        verifyAuthorityMatches(recovered.room)
    }

    private suspend fun verifyAuthorityMatches(expectedRoom: RecoveryRoomSnapshot) {
        val current = try {
            RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoomSessionAuthorityException) {
            throw mapAuthority(e)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        if (current != expectedRoom) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
    }

    private suspend fun verifyCapturedEntries(
        entries: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
    ) {
        for (e in entries) {
            val type = try {
                AggregateType.fromWire(e.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            verifyOutboxRowStrict(
                type = type,
                aggregateId = e.aggregateId,
                mutationId = e.mutationId,
                localGeneration = e.localGeneration,
                intendedPayload = e.payloadJson,
                accountId = target.accountId,
            )
            try {
                val decoded = readDurableOutboxRowStrict(e.mutationId, target.accountId)
                if (decoded.envelope.kind != MutationKind.AGGREGATE_IMPORT) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (decoded.envelope.aggregateType != type ||
                    decoded.envelope.aggregateId != e.aggregateId ||
                    decoded.envelope.localGeneration != e.localGeneration
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (decoded.affectedRoots.size != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val effect = decoded.affectedRoots[0]
                if (effect.aggregateType != type || effect.aggregateId != e.aggregateId ||
                    effect.localGeneration != e.localGeneration
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: LegacyImportException) {
                throw e2
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
    }

    private suspend fun verifyNoWritesAfterEmpty() {
        try {
            val dao = database.syncLegacyImportDao()
            if (dao.hasJournal() || dao.hasEntries()) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
        requireNoProgressOrReceipts()
    }

    /**
     * Orphan check for journal-less/blocked states: any retained progress
     * anchor or acceptance receipt without a captured journal is corrupt
     * and must never read as clean empty/captured.
     */
    private suspend fun requireNoProgressOrReceipts() {
        try {
            currentCoroutineContext().ensureActive()
            if (LegacyImportSettlement.readProgressStrict(database) != null ||
                LegacyImportSettlement.readAcceptancesStrict(database).isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun readLocalGenerationsStrict(): Map<Pair<AggregateType, String>, Long> {
        try {
            val out = HashMap<Pair<AggregateType, String>, Long>()
            val db = database.openHelper.writableDatabase
            db.query(
                "SELECT id, local_generation FROM workouts WHERE account_id IS NULL",
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getType(0) != Cursor.FIELD_TYPE_STRING ||
                        c.getType(1) != Cursor.FIELD_TYPE_INTEGER
                    ) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    out[AggregateType.WORKOUT to c.getString(0)] = c.getLong(1)
                }
            }
            db.query(
                "SELECT id, local_generation FROM routines WHERE account_id IS NULL",
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getType(0) != Cursor.FIELD_TYPE_STRING ||
                        c.getType(1) != Cursor.FIELD_TYPE_INTEGER
                    ) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    out[AggregateType.ROUTINE to c.getString(0)] = c.getLong(1)
                }
            }
            db.query(
                """SELECT id, local_generation FROM exercises
                    WHERE account_id IS NULL
                        AND (is_user_created != 0 OR typeof(is_user_created) != 'integer')""",
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getType(0) != Cursor.FIELD_TYPE_STRING ||
                        c.getType(1) != Cursor.FIELD_TYPE_INTEGER
                    ) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    out[AggregateType.CUSTOM_EXERCISE to c.getString(0)] = c.getLong(1)
                }
            }
            return out
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun readOutboxHighWater(): Long {
        try {
            database.openHelper.writableDatabase.query(
                "SELECT seq FROM sqlite_sequence WHERE name = 'sync_outbox'",
            ).use { c ->
                if (c.count == 0) return 0L
                if (c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                c.moveToFirst()
                if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val seq = c.getLong(0)
                if (seq < 0L) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                return seq
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun verifyRootMetadataStrict(
        type: AggregateType,
        id: String,
        accountId: String?,
        expectedState: String?,
        expectedGeneration: Long?,
    ) {
        try {
            val table = when (type) {
                AggregateType.WORKOUT -> "workouts"
                AggregateType.ROUTINE -> "routines"
                AggregateType.CUSTOM_EXERCISE -> "exercises"
            }
            database.openHelper.writableDatabase.query(
                "SELECT account_id, server_revision, local_generation, sync_state, deleted_at_millis FROM $table WHERE id = ?",
                arrayOf<Any>(id),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (accountId == null) {
                    if (c.getType(0) != Cursor.FIELD_TYPE_NULL) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                } else {
                    if (c.getType(0) != Cursor.FIELD_TYPE_STRING || c.getString(0) != accountId) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                if (c.getType(1) != Cursor.FIELD_TYPE_NULL) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(2) != Cursor.FIELD_TYPE_INTEGER) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val gen = c.getLong(2)
                if (expectedGeneration != null && gen != expectedGeneration) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (expectedState != null) {
                    if (c.getType(3) != Cursor.FIELD_TYPE_STRING || c.getString(3) != expectedState) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                if (c.getType(4) != Cursor.FIELD_TYPE_NULL) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun verifyLedgerStrict(
        type: AggregateType,
        id: String,
        accountId: String,
        generation: Long,
    ) {
        try {
            database.openHelper.writableDatabase.query(
                """SELECT server_revision, canonical_state, canonical_json,
                    local_generation_watermark, cache_epoch FROM sync_canonical_roots
                    WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?""",
                arrayOf<Any>(accountId, type.wireValue, id),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(0) != Cursor.FIELD_TYPE_NULL) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(1) != Cursor.FIELD_TYPE_STRING || c.getString(1) != "unobserved") {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(2) != Cursor.FIELD_TYPE_NULL) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(3) != Cursor.FIELD_TYPE_INTEGER || c.getLong(3) != generation) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(4) != Cursor.FIELD_TYPE_INTEGER || c.getLong(4) != 0L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun verifyAccountCacheStrict(accountId: String) {
        try {
            database.openHelper.writableDatabase.query(
                """SELECT baseline_complete, cache_epoch, projection_dirty, materialized_epoch
                    FROM sync_account_cache WHERE account_id = ?""",
                arrayOf<Any>(accountId),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                for (i in 0..3) {
                    if (c.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                if (c.getLong(0) != 0L || c.getLong(1) != 0L || c.getLong(2) != 1L || c.getLong(3) != 0L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun verifyOutboxRowStrict(
        type: AggregateType,
        aggregateId: String,
        mutationId: String,
        localGeneration: Long,
        intendedPayload: String,
        accountId: String,
    ) {
        try {
            database.openHelper.writableDatabase.query(
                """SELECT account_id, aggregate_type, aggregate_id, mutation_type, mutation_id,
                    local_generation, payload_version, payload_json, sequence
                    FROM sync_outbox WHERE mutation_id = ? AND account_id = ?""",
                arrayOf<Any>(mutationId, accountId),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                for (i in listOf(0, 1, 2, 3, 4, 7)) {
                    if (c.getType(i) != Cursor.FIELD_TYPE_STRING) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                for (i in listOf(5, 6, 8)) {
                    if (c.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                if (c.getString(0) != accountId || c.getString(1) != type.wireValue ||
                    c.getString(2) != aggregateId ||
                    c.getString(3) != MutationKind.AGGREGATE_IMPORT.wireValue ||
                    c.getString(4) != mutationId || c.getLong(5) != localGeneration ||
                    c.getLong(6) != DURABLE_PAYLOAD_VERSION.toLong() || c.getString(7) != intendedPayload ||
                    c.getLong(8) <= 0L
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun readOutboxSequenceStrict(mutationId: String, accountId: String): Long {
        try {
            database.openHelper.writableDatabase.query(
                "SELECT sequence FROM sync_outbox WHERE mutation_id = ? AND account_id = ?",
                arrayOf<Any>(mutationId, accountId),
            ).use { c ->
                if (!c.moveToFirst() || c.count != 1) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val seq = c.getLong(0)
                if (seq <= 0L) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                return seq
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun readDurableOutboxRowStrict(
        mutationId: String,
        accountId: String,
    ): com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel {
        try {
            val stored = database.syncDao().getOutboxMutation(mutationId, accountId)
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            return readDurableOutboxRow(stored).request
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private suspend fun readJournalStrict(): SyncLegacyImportEntity? {
        try {
            database.syncLegacyImportDao().observeImportRows().use { cursor ->
                if (cursor.count == 0) return null
                if (cursor.count != 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                cursor.moveToFirst()
                if (cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER || cursor.getLong(0) != 1L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (cursor.getType(1) != Cursor.FIELD_TYPE_INTEGER) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val version = cursor.getLong(1)
                if (version != LegacyImportSource.EVIDENCE_VERSION) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                for (i in 2..4) {
                    if (cursor.getType(i) != Cursor.FIELD_TYPE_STRING) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                for (i in 5..8) {
                    if (cursor.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                if (cursor.getType(9) != Cursor.FIELD_TYPE_STRING) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (cursor.getType(10) != Cursor.FIELD_TYPE_STRING) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (cursor.getType(11) != Cursor.FIELD_TYPE_INTEGER) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (cursor.getType(12) != Cursor.FIELD_TYPE_NULL &&
                    cursor.getType(12) != Cursor.FIELD_TYPE_STRING
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                return SyncLegacyImportEntity(
                    evidenceVersion = version,
                    serviceOrigin = cursor.getString(2),
                    accountId = cursor.getString(3),
                    operationId = cursor.getString(4),
                    sourceRevision = cursor.getLong(5),
                    sourceSessionGeneration = cursor.getLong(6),
                    roomRevision = cursor.getLong(7),
                    roomSessionGeneration = cursor.getLong(8),
                    phase = cursor.getString(9),
                    sourceJson = cursor.getString(10),
                    importCount = cursor.getLong(11),
                    errorCode = if (cursor.getType(12) == Cursor.FIELD_TYPE_NULL) null else cursor.getString(12),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun readEntriesStrict(): List<SyncLegacyImportEntryEntity> {
        try {
            database.syncLegacyImportDao().observeEntryRows().use { cursor ->
                val out = ArrayList<SyncLegacyImportEntryEntity>(cursor.count)
                while (cursor.moveToNext()) {
                    for (i in 0..7) {
                        val expectedString = i == 1 || i == 2 || i == 3 || i == 7
                        if (expectedString) {
                            if (cursor.getType(i) != Cursor.FIELD_TYPE_STRING) {
                                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                            }
                        } else {
                            if (cursor.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                            }
                        }
                    }
                    out.add(
                        SyncLegacyImportEntryEntity(
                            ordinal = cursor.getLong(0),
                            aggregateType = cursor.getString(1),
                            aggregateId = cursor.getString(2),
                            mutationId = cursor.getString(3),
                            sequence = cursor.getLong(4),
                            localGeneration = cursor.getLong(5),
                            payloadVersion = cursor.getLong(6),
                            payloadJson = cursor.getString(7),
                        ),
                    )
                }
                out.sortBy { it.ordinal }
                return out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun acquireBarrier(expected: RecoveryRoomSnapshot): ProjectionPublicationLease {
        return try {
            currentCoroutineContext().ensureActive()
            barrier.acquire(expected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun runProcess() {
        try {
            currentCoroutineContext().ensureActive()
            processCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private suspend fun checkLease(lease: ProjectionPublicationLease) {
        try {
            currentCoroutineContext().ensureActive()
            lease.checkValid()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private fun closeThrowing(lease: ProjectionPublicationLease) {
        try {
            lease.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    private fun mapAuthority(e: RoomSessionAuthorityException): LegacyImportException {
        return when (e.failure) {
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.INVALID_INPUT ->
                LegacyImportException(LegacyImportFailure.INVALID_INPUT)
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.STALE_AUTHORITY ->
                LegacyImportException(LegacyImportFailure.STALE_AUTHORITY)
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.ACCOUNT_MISMATCH ->
                LegacyImportException(LegacyImportFailure.FOREIGN_STORAGE)
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.LOCAL_DATA_REQUIRES_DECISION ->
                LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.COUNTER_OVERFLOW ->
                LegacyImportException(LegacyImportFailure.COUNTER_OVERFLOW)
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.CORRUPT_AUTHORITY ->
                LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityFailure.UNAVAILABLE ->
                LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    override fun toString(): String = "LegacyImportStore(redacted)"
}
