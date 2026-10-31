package com.dragosfotache.gymtracka.data.sync.legacy

import android.database.Cursor
import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportAcceptanceEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportEntryEntity
import com.dragosfotache.gymtracka.data.gym.SyncLegacyImportProgressEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.decodeSyncBatch
import com.dragosfotache.gymtracka.data.sync.codec.encodeImportRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeSyncBatch
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * consolidated strict progress/acceptance validator for the private
 * import. Single authority for journal/entry/progress/receipt validation:
 * raw SQL scalar types/counts only, no coercive DAO success. Any duplicate,
 * orphan, malformed, mismatched, nonconsecutive-prefix or anchor failure
 * throws closed CORRUPT_EVIDENCE. Displays are fixed/redacted.
 */
internal object LegacyImportSettlement {
    internal data class View(
        val journal: SyncLegacyImportEntity,
        val entries: List<SyncLegacyImportEntryEntity>,
        val progress: SyncLegacyImportProgressEntity,
        val acceptances: List<SyncLegacyImportAcceptanceEntity>,
        val acceptedPrefix: Int,
        val activeMutationId: String?,
        /** Validated receipt batch per accepted ordinal. */
        val receiptBatches: List<SyncBatchModel>,
    ) {
        override fun toString(): String = "LegacyImportSettlement.View(redacted)"
    }

    suspend fun readJournalStrict(database: GymDatabase): SyncLegacyImportEntity? {
        try {
            database.syncLegacyImportDao().observeImportRows().use { c ->
                if (c.count == 0) return null
                if (c.count != 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                c.moveToFirst()
                if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) != 1L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(1) != Cursor.FIELD_TYPE_INTEGER) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                for (i in 2..4) {
                    if (c.getType(i) != Cursor.FIELD_TYPE_STRING) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                for (i in 5..8) {
                    if (c.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                }
                if (c.getType(9) != Cursor.FIELD_TYPE_STRING ||
                    c.getType(10) != Cursor.FIELD_TYPE_STRING ||
                    c.getType(11) != Cursor.FIELD_TYPE_INTEGER
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(12) != Cursor.FIELD_TYPE_NULL && c.getType(12) != Cursor.FIELD_TYPE_STRING) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                return SyncLegacyImportEntity(
                    evidenceVersion = c.getLong(1),
                    serviceOrigin = c.getString(2),
                    accountId = c.getString(3),
                    operationId = c.getString(4),
                    sourceRevision = c.getLong(5),
                    sourceSessionGeneration = c.getLong(6),
                    roomRevision = c.getLong(7),
                    roomSessionGeneration = c.getLong(8),
                    phase = c.getString(9),
                    sourceJson = c.getString(10),
                    importCount = c.getLong(11),
                    errorCode = if (c.getType(12) == Cursor.FIELD_TYPE_NULL) null else c.getString(12),
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

    suspend fun readEntriesStrict(database: GymDatabase): List<SyncLegacyImportEntryEntity> {
        try {
            database.syncLegacyImportDao().observeEntryRows().use { c ->
                val out = ArrayList<SyncLegacyImportEntryEntity>(c.count)
                while (c.moveToNext()) {
                    for (i in 0..7) {
                        val wantString = i == 1 || i == 2 || i == 3 || i == 7
                        if (wantString) {
                            if (c.getType(i) != Cursor.FIELD_TYPE_STRING) {
                                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                            }
                        } else {
                            if (c.getType(i) != Cursor.FIELD_TYPE_INTEGER) {
                                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                            }
                        }
                    }
                    out.add(
                        SyncLegacyImportEntryEntity(
                            ordinal = c.getLong(0),
                            aggregateType = c.getString(1),
                            aggregateId = c.getString(2),
                            mutationId = c.getString(3),
                            sequence = c.getLong(4),
                            localGeneration = c.getLong(5),
                            payloadVersion = c.getLong(6),
                            payloadJson = c.getString(7),
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

    suspend fun readProgressStrict(database: GymDatabase): SyncLegacyImportProgressEntity? {
        try {
            database.syncLegacyImportDao().observeProgressRows().use { c ->
                if (c.count == 0) return null
                if (c.count != 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                c.moveToFirst()
                if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) != 1L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (c.getType(1) != Cursor.FIELD_TYPE_INTEGER ||
                    c.getType(2) != Cursor.FIELD_TYPE_INTEGER ||
                    c.getType(3) != Cursor.FIELD_TYPE_STRING
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val opType = c.getType(4)
                if (opType != Cursor.FIELD_TYPE_NULL && opType != Cursor.FIELD_TYPE_STRING) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val consentType = c.getType(5)
                if (consentType != Cursor.FIELD_TYPE_NULL && consentType != Cursor.FIELD_TYPE_STRING) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val phase = c.getString(3)
                if (phase != "preparing" && phase != "transitioning" &&
                    phase != "active" && phase != "signed_out"
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                return SyncLegacyImportProgressEntity(
                    roomRevision = c.getLong(1),
                    roomSessionGeneration = c.getLong(2),
                    roomPhase = phase,
                    operationId = if (opType == Cursor.FIELD_TYPE_NULL) null else c.getString(4),
                    activeConsentMutationId = if (consentType == Cursor.FIELD_TYPE_NULL) null else c.getString(5),
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

    suspend fun readAcceptancesStrict(database: GymDatabase): List<SyncLegacyImportAcceptanceEntity> {
        try {
            database.syncLegacyImportDao().observeAcceptanceRows().use { c ->
                val out = ArrayList<SyncLegacyImportAcceptanceEntity>(c.count)
                while (c.moveToNext()) {
                    if (c.getType(0) != Cursor.FIELD_TYPE_INTEGER ||
                        c.getType(1) != Cursor.FIELD_TYPE_STRING ||
                        c.getType(2) != Cursor.FIELD_TYPE_STRING
                    ) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    out.add(
                        SyncLegacyImportAcceptanceEntity(
                            ordinal = c.getLong(0),
                            mutationId = c.getString(1),
                            batchJson = c.getString(2),
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

    fun parseTargetStrict(journal: SyncLegacyImportEntity): LegacyImportTarget {
        try {
            return LegacyImportTarget(
                origin = LegacyImportSource.parseCanonicalOrigin(journal.serviceOrigin),
                accountId = journal.accountId,
                operationId = journal.operationId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Full journal/entry/source validation: version, counters, room/source
     * linkage, phase/count invariants, entry order/identity/g+1, source
     * correspondence, and every original durable wrapper/envelope/affected
     * root decoded from the immutable entry bytes (no outbox needed, so
     * settled prefixes validate after head removal).
     */
    fun validateJournalFull(
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
    ): JSONObject {
        try {
            if (journal.evidenceVersion != LegacyImportSource.EVIDENCE_VERSION) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (journal.serviceOrigin != LegacyImportSource.canonicalOriginText(target.origin) ||
                journal.accountId != target.accountId ||
                journal.operationId != target.operationId
            ) {
                throw LegacyImportException(LegacyImportFailure.TARGET_MISMATCH)
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
            if (journal.phase != LegacyImportSource.PHASE_CAPTURED) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (journal.errorCode != null) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val source = try {
                LegacyImportSource.validateSourceJson(journal.sourceJson)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val expected = try {
                LegacyImportSource.expectedEntriesFromSource(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (journal.importCount != entries.size.toLong() || entries.isEmpty() ||
                expected.size != entries.size
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            entries.forEachIndexed { index, e ->
                if (e.ordinal != index.toLong()) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val type = try {
                    AggregateType.fromWire(e.aggregateType)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (e.sequence <= 0L || e.localGeneration < 1L ||
                    e.payloadVersion != DURABLE_PAYLOAD_VERSION.toLong()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (index > 0 && e.sequence <= entries[index - 1].sequence) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val (wantType, wantId, originalGen) = expected[index]
                if (e.aggregateType != wantType.wireValue || e.aggregateId != wantId) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (originalGen == Long.MAX_VALUE || e.localGeneration != originalGen + 1L) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                validateEntryWrapper(e, type)
            }
            if (entries.map { it.mutationId }.toSet().size != entries.size ||
                entries.map { it.sequence }.toSet().size != entries.size
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            for (e in entries) {
                validateEntryPayloadAgainstSource(e, source)
            }
            validateSourceClosure(source, entries)
            return source
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Validates one immutable original durable wrapper/envelope/affected
     * root from entry bytes alone. Survives outbox/domain removal.
     */
    private fun validateEntryWrapper(e: SyncLegacyImportEntryEntity, type: AggregateType) {
        try {
            val decoded = decodeDurableRequest(parseStrictJsonObject(e.payloadJson))
            if (decoded.payloadVersion != DURABLE_PAYLOAD_VERSION) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (decoded.envelope.mutationId != e.mutationId) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
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

    /**
     * Validates a receipt batch strictly: requires the expected imported
     * root UPSERT with matching type/id and a positive revision, and full
     * record business equality with the original imported input (derived
     * labels and revision ignored).
     */
    fun validateReceiptBatch(
        batchJson: String,
        entry: SyncLegacyImportEntryEntity,
        source: JSONObject,
    ): SyncBatchModel {
        try {
            val batch = decodeSyncBatch(parseStrictJsonObject(batchJson))
            encodeSyncBatch(batch)
            val type = try {
                AggregateType.fromWire(entry.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val change = batch.changes.firstOrNull {
                it.aggregateType == type && it.aggregateId == entry.aggregateId
            } ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (change.operation != ChangeOperation.UPSERT || change.revision <= 0L) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val expected = expectedRecordFor(entry, source)
            if (!receiptRecordMatchesInput(change.record, expected)) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            return batch
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Requires a canonical receipt record to carry exactly the original
     * imported business (children/order/relationships/optional/nulls).
     * Derived display labels and revision are ignored; everything else
     * must match the authoritative expected input.
     */
    private fun receiptRecordMatchesInput(record: CanonicalRecord?, expected: ImportRecord): Boolean {
        try {
            return when {
                record is CanonicalRecord.Workout && expected is ImportRecord.Workout -> {
                    val r = record.record
                    val w = expected.input
                    r.id == w.id && r.name == w.name && r.status == w.status &&
                        r.startedAtMillis == w.startedAtMillis && r.finishedAtMillis == w.finishedAtMillis &&
                        r.exercises.size == w.exercises.size && r.exercises.zip(w.exercises).all { (re, we) ->
                            re.id == we.id && re.exerciseId == we.exerciseId && re.position == we.position &&
                                re.notes == we.notes && re.restDurationSeconds == we.restDurationSeconds &&
                                re.sets.size == we.sets.size && re.sets.zip(we.sets).all { (rs, ws) ->
                                    rs.id == ws.id && rs.position == ws.position &&
                                        rs.repetitions == ws.repetitions && rs.weightKg == ws.weightKg &&
                                        rs.isCompleted == ws.isCompleted && rs.restSeconds == ws.restSeconds
                                }
                        }
                }
                record is CanonicalRecord.Routine && expected is ImportRecord.Routine -> {
                    val r = record.record
                    val t = expected.input
                    r.id == t.id && r.name == t.name && r.isDraft == t.isDraft &&
                        r.exercises.size == t.exercises.size && r.exercises.zip(t.exercises).all { (re, te) ->
                            re.id == te.id && re.exerciseId == te.exerciseId && re.position == te.position &&
                                re.restDurationSeconds == te.restDurationSeconds &&
                                re.sets.size == te.sets.size && re.sets.zip(te.sets).all { (rs, ts) ->
                                    rs.id == ts.id && rs.position == ts.position &&
                                        rs.repetitions == ts.repetitions && rs.weightKg == ts.weightKg
                                }
                        }
                }
                record is CanonicalRecord.CustomExercise && expected is ImportRecord.CustomExercise -> {
                    val r = record.record
                    val c = expected.input
                    if (r.type != "reps") return false
                    r.id == c.id && r.name == c.name && r.category == c.category &&
                        r.difficultyLevel == c.difficultyLevel && r.forceType == c.forceType &&
                        r.mechanics == c.mechanics && r.instructions == c.instructions &&
                        r.muscleGroups.size == (if (c.primaryMuscleGroupId == null) 0 else 1) + c.secondaryMuscleGroupIds.size &&
                        (c.primaryMuscleGroupId == null || r.muscleGroups.any { it.id == c.primaryMuscleGroupId && it.type == "primary" }) &&
                        c.secondaryMuscleGroupIds.all { want ->
                            r.muscleGroups.any { it.id == want && it.type == "secondary" }
                        } &&
                        (c.equipmentId == null || r.equipment.any { it.id == c.equipmentId && it.numItems == c.equipmentNumItems }) &&
                        (c.equipmentId != null || r.equipment.isEmpty())
                }
                else -> false
            }
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Validates one decoded original payload against the authoritative
     * frozen source: reconstructs the complete expected import input
     * (root, children/order/relationships, optional/null fields) and
     * requires exact canonical equality. Comparison only; wrapper bytes
     * and IDs are never regenerated.
     */
    private fun validateEntryPayloadAgainstSource(e: SyncLegacyImportEntryEntity, source: JSONObject) {
        try {
            val decoded = decodeDurableRequest(parseStrictJsonObject(e.payloadJson))
            val envelope = try {
                decodeMutationEnvelope(parseStrictJsonObject(decoded.requestJson))
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val payload = envelope.payload
            if (payload !is MutationPayload.AggregateImport) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val expected = expectedRecordFor(e, source)
            if (encodeImportRecord(payload.record).toString() != encodeImportRecord(expected).toString()) {
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

    private fun sourceRows(source: JSONObject, key: String): List<JSONArray> {
        try {
            val array = source.getJSONArray(key)
            return List(array.length()) { i ->
                try {
                    array.getJSONArray(i)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellIsNull(row: JSONArray, index: Int): Boolean {
        try {
            return row.getJSONObject(index).getString("t") == "null"
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellLabel(row: JSONArray, index: Int): String {
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

    private fun cellOptLabel(row: JSONArray, index: Int): String? {
        if (cellIsNull(row, index)) return null
        return cellLabel(row, index)
    }

    private fun cellLong(row: JSONArray, index: Int): Long {
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

    private fun cellOptLong(row: JSONArray, index: Int): Long? {
        if (cellIsNull(row, index)) return null
        return cellLong(row, index)
    }

    private fun cellFlag(row: JSONArray, index: Int): Boolean {
        return when (cellLong(row, index)) {
            0L -> false
            1L -> true
            else -> throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellNonNegInt(row: JSONArray, index: Int): Int {
        val v = cellLong(row, index)
        if (v < 0L || v > Int.MAX_VALUE) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        return v.toInt()
    }

    private fun cellOptNonNegInt(row: JSONArray, index: Int): Int? {
        val v = cellOptLong(row, index) ?: return null
        if (v < 0L || v > Int.MAX_VALUE) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        return v.toInt()
    }

    private fun cellOptWeight(row: JSONArray, index: Int): Double? {
        try {
            val cell = row.getJSONObject(index)
            return when (cell.getString("t")) {
                "null" -> null
                "int" -> cell.getString("v").toLong().toDouble()
                "float" -> Double.fromBits(cell.getString("v").toLong())
                else -> throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun cellInstructions(row: JSONArray, index: Int): List<String>? {
        try {
            val cell = row.getJSONObject(index)
            if (cell.getString("t") == "null") return null
            if (cell.getString("t") != "text") throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val array = try {
                org.json.JSONArray(cell.getString("v"))
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            return List(array.length()) { i ->
                val element = try {
                    array.get(i)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (element !is String) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                element
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Reconstructs the authoritative expected import record for one entry
     * from frozen source rows, mirroring capture's validated graph shape
     * (children grouped by parent with contiguous positions, single
     * equipment/primary relationships, validated input models).
     */
    private fun expectedRecordFor(e: SyncLegacyImportEntryEntity, source: JSONObject): ImportRecord {
        try {
            val type = try {
                AggregateType.fromWire(e.aggregateType)
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            return when (type) {
                AggregateType.CUSTOM_EXERCISE -> ImportRecord.CustomExercise(expectedCustomInput(e.aggregateId, source))
                AggregateType.ROUTINE -> ImportRecord.Routine(expectedRoutineInput(e.aggregateId, source))
                AggregateType.WORKOUT -> ImportRecord.Workout(expectedWorkoutImport(e.aggregateId, source))
            }
        } catch (e2: CancellationException) {
            throw e2
        } catch (e2: LegacyImportException) {
            throw e2
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun expectedCustomInput(id: String, source: JSONObject): CustomExerciseInputModel {
        try {
            val row = sourceRows(source, "exercises").singleOrNull { cellLabel(it, 0) == id }
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (row.length() != 14) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (cellLabel(row, 2) != "reps") throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (!cellFlag(row, 8)) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val rels = sourceRows(source, "exercise_muscle_groups").filter { cellLabel(it, 0) == id }
            val primaries = rels.filter { cellLabel(it, 2) == "primary" }
            if (primaries.size > 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            for (r in rels) {
                val role = cellLabel(r, 2)
                if (role != "primary" && role != "secondary") {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
            val eqRows = sourceRows(source, "exercise_equipment").filter { cellLabel(it, 0) == id }
            if (eqRows.size > 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val eq = eqRows.singleOrNull()
            val qty = eq?.let {
                if (it.length() != 3) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                cellOptNonNegInt(it, 2)
            }
            try {
                return validateCustomExerciseInputModel(
                    CustomExerciseInputModel(
                        id = id,
                        name = cellLabel(row, 1),
                        primaryMuscleGroupId = primaries.singleOrNull()?.let { cellLabel(it, 1) },
                        secondaryMuscleGroupIds = rels.filter { cellLabel(it, 2) == "secondary" }.map { cellLabel(it, 1) },
                        equipmentId = eq?.let { cellLabel(it, 1) },
                        equipmentNumItems = qty,
                        category = cellOptLabel(row, 6),
                        difficultyLevel = cellOptLabel(row, 3),
                        forceType = cellOptLabel(row, 4),
                        mechanics = cellOptLabel(row, 5),
                        instructions = cellInstructions(row, 7),
                    ),
                )
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun expectedRoutineInput(id: String, source: JSONObject): RoutineInputModel {
        try {
            val row = sourceRows(source, "routines").singleOrNull { cellLabel(it, 0) == id }
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (row.length() != 8) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val childRows = sourceRows(source, "routine_exercises")
                .filter { cellLabel(it, 1) == id }.sortedBy { cellNonNegInt(it, 3) }
            val setRows = sourceRows(source, "routine_sets")
            val entries = childRows.mapIndexed { index, c ->
                if (c.length() != 5) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                if (cellNonNegInt(c, 3) != index) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                val sets = setRows.filter { cellLabel(it, 1) == cellLabel(c, 0) }
                    .sortedBy { cellNonNegInt(it, 2) }
                RoutineExerciseInputModel(
                    id = cellLabel(c, 0),
                    exerciseId = cellLabel(c, 2),
                    position = index,
                    restDurationSeconds = cellNonNegInt(c, 4),
                    sets = sets.mapIndexed { sIndex, s ->
                        if (s.length() != 5) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        if (cellNonNegInt(s, 2) != sIndex) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        RoutineSetModel(
                            id = cellLabel(s, 0),
                            position = sIndex,
                            repetitions = cellOptNonNegInt(s, 3),
                            weightKg = cellOptWeight(s, 4),
                        )
                    },
                )
            }
            try {
                return validateRoutineInputModel(
                    RoutineInputModel(
                        id = id,
                        name = cellLabel(row, 1),
                        isDraft = cellFlag(row, 2),
                        exercises = entries,
                    ),
                )
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun expectedWorkoutImport(id: String, source: JSONObject): WorkoutImportModel {
        try {
            val row = sourceRows(source, "workouts").singleOrNull { cellLabel(it, 0) == id }
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (row.length() != 10) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val status = cellLabel(row, 4)
            if (status != "active" && status != "finished") {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val childRows = sourceRows(source, "workout_exercises")
                .filter { cellLabel(it, 1) == id }.sortedBy { cellNonNegInt(it, 3) }
            val setRows = sourceRows(source, "workout_sets")
            val entries = childRows.mapIndexed { index, c ->
                if (c.length() != 6) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                if (cellNonNegInt(c, 3) != index) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                val sets = setRows.filter { cellLabel(it, 1) == cellLabel(c, 0) }
                    .sortedBy { cellNonNegInt(it, 2) }
                WorkoutExerciseInputModel(
                    id = cellLabel(c, 0),
                    exerciseId = cellLabel(c, 2),
                    position = index,
                    notes = cellLabel(c, 4),
                    restDurationSeconds = cellNonNegInt(c, 5),
                    sets = sets.mapIndexed { sIndex, s ->
                        if (s.length() != 7) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        if (cellNonNegInt(s, 2) != sIndex) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        WorkoutSetModel(
                            id = cellLabel(s, 0),
                            position = sIndex,
                            repetitions = cellOptNonNegInt(s, 3),
                            weightKg = cellOptWeight(s, 4),
                            isCompleted = cellFlag(s, 5),
                            restSeconds = cellOptNonNegInt(s, 6),
                        )
                    },
                )
            }
            try {
                return validateWorkoutImportModel(
                    WorkoutImportModel(
                        id = id,
                        name = cellLabel(row, 1),
                        status = status,
                        startedAtMillis = cellLong(row, 2),
                        finishedAtMillis = cellOptLong(row, 3),
                        exercises = entries,
                    ),
                )
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Source-closure validation for captured evidence: every frozen row
     * must be uniquely accounted for by valid parent/root relationships
     * in its own family namespace (no orphan/unconsumed/duplicate rows),
     * and every original root must carry genuinely local-only provenance
     * (NULL account/revision/deletion, local_only state, nonnegative
     * generation, custom ownership/type guards). Cross-family UUIDs stay
     * independent; only same-table duplicates and dangling parents fail.
     * Blocked/raw-archive parsing is untouched.
     */
    private fun validateSourceClosure(source: JSONObject, entries: List<SyncLegacyImportEntryEntity>) {
        try {
            val workoutIds = entries.filter { it.aggregateType == AggregateType.WORKOUT.wireValue }
                .map { it.aggregateId }.toSet()
            val routineIds = entries.filter { it.aggregateType == AggregateType.ROUTINE.wireValue }
                .map { it.aggregateId }.toSet()
            val customIds = entries.filter { it.aggregateType == AggregateType.CUSTOM_EXERCISE.wireValue }
                .map { it.aggregateId }.toSet()
            if (workoutIds.size + routineIds.size + customIds.size != entries.size) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val workoutRows = sourceRows(source, "workouts")
            requireRootClosure(workoutRows, workoutIds, 0, 7, 5, 6, 8, 9)
            val routineRows = sourceRows(source, "routines")
            requireRootClosure(routineRows, routineIds, 0, 5, 3, 4, 6, 7)
            val customRows = sourceRows(source, "exercises")
            val customRowIds = HashSet<String>(customRows.size)
            for (r in customRows) {
                if (r.length() != 14) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                val id = cellLabel(r, 0)
                if (!customRowIds.add(id)) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                requireLocalProvenance(r, 9, 10, 11, 12, 13)
                if (cellLabel(r, 2) != "reps") throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                if (!cellFlag(r, 8)) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (customRowIds != customIds) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            requireChildClosure(sourceRows(source, "workout_exercises"), 6, 0, 1, workoutIds)
            requireChildClosure(sourceRows(source, "workout_sets"), 7, 0, 1, workoutExerciseIds(source))
            requireChildClosure(sourceRows(source, "routine_exercises"), 5, 0, 1, routineIds)
            requireChildClosure(sourceRows(source, "routine_sets"), 5, 0, 1, routineExerciseIds(source))
            val workoutEntryIds = sourceRows(source, "workout_exercises").map { cellLabel(it, 0) }.toSet()
            val workoutSetIds = sourceRows(source, "workout_sets").map { cellLabel(it, 0) }.toSet()
            if (workoutEntryIds.intersect(workoutSetIds).isNotEmpty() ||
                workoutEntryIds.intersect(workoutIds).isNotEmpty() ||
                workoutSetIds.intersect(workoutIds).isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val routineEntryIds = sourceRows(source, "routine_exercises").map { cellLabel(it, 0) }.toSet()
            val routineSetIds = sourceRows(source, "routine_sets").map { cellLabel(it, 0) }.toSet()
            if (routineEntryIds.intersect(routineSetIds).isNotEmpty() ||
                routineEntryIds.intersect(routineIds).isNotEmpty() ||
                routineSetIds.intersect(routineIds).isNotEmpty()
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            for (r in sourceRows(source, "exercise_muscle_groups")) {
                if (r.length() != 3) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                if (cellLabel(r, 0) !in customIds) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            for (r in sourceRows(source, "exercise_equipment")) {
                if (r.length() != 3) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                if (cellLabel(r, 0) !in customIds) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun requireRootClosure(
        rows: List<JSONArray>,
        knownIds: Set<String>,
        idIndex: Int,
        genIndex: Int,
        accountIndex: Int,
        revisionIndex: Int,
        stateIndex: Int,
        deletedIndex: Int,
    ) {
        val seen = HashSet<String>(rows.size)
        for (r in rows) {
            val id = cellLabel(r, idIndex)
            if (!seen.add(id)) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            requireLocalProvenance(r, accountIndex, revisionIndex, genIndex, stateIndex, deletedIndex)
        }
        if (seen != knownIds) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
    }

    private fun requireLocalProvenance(
        row: JSONArray,
        accountIndex: Int,
        revisionIndex: Int,
        generationIndex: Int,
        stateIndex: Int,
        deletedIndex: Int,
    ) {
        try {
            val account = row.getJSONObject(accountIndex)
            val revision = row.getJSONObject(revisionIndex)
            val state = row.getJSONObject(stateIndex)
            val deleted = row.getJSONObject(deletedIndex)
            if (account.getString("t") != "null" ||
                revision.getString("t") != "null" ||
                deleted.getString("t") != "null" ||
                state.getString("t") != "text" || state.getString("v") != "local_only"
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (cellLong(row, generationIndex) < 0L) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    private fun requireChildClosure(
        rows: List<JSONArray>,
        width: Int,
        idIndex: Int,
        parentIndex: Int,
        parentIds: Set<String>,
    ) {
        val seen = HashSet<String>(rows.size)
        for (r in rows) {
            if (r.length() != width) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            val id = cellLabel(r, idIndex)
            if (!seen.add(id)) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (cellLabel(r, parentIndex) !in parentIds) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
    }

    private fun workoutExerciseIds(source: JSONObject): Set<String> {
        val out = HashSet<String>()
        for (r in sourceRows(source, "workout_exercises")) {
            if (r.length() != 6) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (!out.add(cellLabel(r, 0))) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        return out
    }

    private fun routineExerciseIds(source: JSONObject): Set<String> {
        val out = HashSet<String>()
        for (r in sourceRows(source, "routine_exercises")) {
            if (r.length() != 5) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (!out.add(cellLabel(r, 0))) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        return out
    }

    /**
     * Strict active-import detection from frozen source correspondence.
     * Returns the trailing WORKOUT entry mutation when the frozen source
     * carries exactly one active workout, else null when all finished.
     * Any malformed/unmatched shape throws corrupt; absence is never
     * waived into a silent null on corrupt evidence.
     */
    fun detectActiveMutationIdStrict(
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
    ): String? {
        try {
            val root = try {
                LegacyImportSource.validateSourceJson(journal.sourceJson)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val workouts = try {
                root.getJSONArray("workouts")
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            var activeId: String? = null
            for (i in 0 until workouts.length()) {
                val row = try {
                    workouts.getJSONArray(i)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (row.length() != 10) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val idCell = try {
                    row.getJSONObject(0)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val statusCell = try {
                    row.getJSONObject(4)
                } catch (_: Exception) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                if (idCell.optString("t") != "text" || statusCell.optString("t") != "text") {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                when (statusCell.optString("v")) {
                    "active" -> {
                        if (activeId != null) {
                            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                        }
                        activeId = idCell.optString("v")
                    }
                    "finished" -> Unit
                    else -> throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
            val wanted = activeId ?: return null
            val match = entries.singleOrNull { it.aggregateId == wanted && it.aggregateType == AggregateType.WORKOUT.wireValue }
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (entries.last() != match) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            return match.mutationId
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    /**
     * Full settlement view. [current] is the freshly read strict authority
     * tuple: the progress anchor binds to it (revision/generation/lowercase
     * phase/operation, null operation only when signed out) and the current
     * origin/account bind to the target. The journal preserves the ORIGINAL
     * capture operation separately and is never equated with a newer
     * reauthentication operation. Receipts form a consecutive zero-based
     * prefix with validated UPSERT batches; settled prefixes have no
     * retained request while every unsettled entry retains exactly its
     * original request.
     */
    suspend fun validateView(
        database: GymDatabase,
        journal: SyncLegacyImportEntity,
        entries: List<SyncLegacyImportEntryEntity>,
        target: LegacyImportTarget,
        current: RecoveryRoomSnapshot,
    ): View {
        val source = validateJournalFull(journal, entries, target)
        if (current.cachedAccountId != target.accountId) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val identity = current.identity
        if (current.phase != RecoveryRoomPhase.SIGNED_OUT) {
            if (identity == null || identity.binding.accountId != target.accountId) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (identity.binding.origin != target.origin) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        } else {
            if (identity != null) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
        val progress = readProgressStrict(database)
            ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        if (progress.roomRevision < 0L || progress.roomSessionGeneration < 0L) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val anchorPhase = when (current.phase) {
            RecoveryRoomPhase.SIGNED_OUT -> "signed_out"
            RecoveryRoomPhase.PREPARING -> "preparing"
            RecoveryRoomPhase.TRANSITIONING -> "transitioning"
            RecoveryRoomPhase.ACTIVE -> "active"
        }
        if (progress.roomRevision != current.revision ||
            progress.roomSessionGeneration != current.sessionGeneration ||
            progress.roomPhase != anchorPhase ||
            progress.operationId != current.identity?.operationId
        ) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (current.phase == RecoveryRoomPhase.SIGNED_OUT) {
            if (progress.operationId != null) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
        val acceptances = readAcceptancesStrict(database)
        acceptances.forEachIndexed { index, a ->
            if (a.ordinal != index.toLong()) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
        if (acceptances.map { it.mutationId }.toSet().size != acceptances.size) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (acceptances.size > entries.size) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        val batches = ArrayList<SyncBatchModel>(acceptances.size)
        for (i in acceptances.indices) {
            val e = entries[i]
            val a = acceptances[i]
            if (a.mutationId != e.mutationId) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            batches.add(validateReceiptBatch(a.batchJson, e, source))
        }
        validateRetainedSuffixBodies(database, entries, acceptances.size, target)
        val activeMutationId = detectActiveMutationIdStrict(journal, entries)
        val consent = progress.activeConsentMutationId
        if (consent != null && (activeMutationId == null || consent != activeMutationId)) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
        if (activeMutationId != null && acceptances.isNotEmpty()) {
            val activeIndex = entries.indexOfFirst { it.mutationId == activeMutationId }
            if (activeIndex < 0) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (activeIndex < acceptances.size && consent != activeMutationId) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
        }
        return View(journal, entries, progress, acceptances, acceptances.size, activeMutationId, batches)
    }

    /**
     * Retained-suffix body validation for read/preflight paths: every
     * unsettled entry retains exactly its original request (full row
     * identity plus exact bytes, envelope and affected roots), while every
     * settled entry has no retained request. Corrupt non-head bodies fail
     * before any HTTP.
     */
    private suspend fun validateRetainedSuffixBodies(
        database: GymDatabase,
        entries: List<SyncLegacyImportEntryEntity>,
        acceptedPrefix: Int,
        target: LegacyImportTarget,
    ) {
        for (i in entries.indices) {
            val e = entries[i]
            val row = readOutboxRowRaw(database, e.mutationId, target.accountId)
            if (i < acceptedPrefix) {
                if (row != null) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                continue
            }
            if (row == null) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (row.accountId != target.accountId || row.aggregateType != e.aggregateType ||
                row.aggregateId != e.aggregateId || row.mutationType != MutationKind.AGGREGATE_IMPORT.wireValue ||
                row.mutationId != e.mutationId || row.localGeneration != e.localGeneration ||
                row.payloadVersion != DURABLE_PAYLOAD_VERSION.toLong() || row.payloadJson != e.payloadJson ||
                row.sequence != e.sequence
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            try {
                val decoded = decodeDurableRequest(parseStrictJsonObject(row.payloadJson))
                if (decoded.payloadVersion != DURABLE_PAYLOAD_VERSION ||
                    decoded.envelope.kind != MutationKind.AGGREGATE_IMPORT ||
                    decoded.envelope.mutationId != e.mutationId
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val type = AggregateType.fromWire(e.aggregateType)
                if (decoded.envelope.aggregateType != type ||
                    decoded.envelope.aggregateId != e.aggregateId ||
                    decoded.envelope.localGeneration != e.localGeneration ||
                    decoded.affectedRoots.size != 1
                ) {
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

    private data class RawOutboxRow(
        val accountId: String,
        val aggregateType: String,
        val aggregateId: String,
        val mutationType: String,
        val mutationId: String,
        val localGeneration: Long,
        val payloadVersion: Long,
        val payloadJson: String,
        val sequence: Long,
    )

    private suspend fun readOutboxRowRaw(
        database: GymDatabase,
        mutationId: String,
        accountId: String,
    ): RawOutboxRow? {
        try {
            database.openHelper.writableDatabase.query(
                """SELECT account_id, aggregate_type, aggregate_id, mutation_type, mutation_id,
                    local_generation, payload_version, payload_json, sequence
                    FROM sync_outbox WHERE mutation_id = ? AND account_id = ?""",
                arrayOf<Any>(mutationId, accountId),
            ).use { c ->
                if (c.count == 0) return null
                if (c.count != 1) throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                c.moveToFirst()
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
                return RawOutboxRow(
                    accountId = c.getString(0),
                    aggregateType = c.getString(1),
                    aggregateId = c.getString(2),
                    mutationType = c.getString(3),
                    mutationId = c.getString(4),
                    localGeneration = c.getLong(5),
                    payloadVersion = c.getLong(6),
                    payloadJson = c.getString(7),
                    sequence = c.getLong(8),
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

    /**
     * Owned pinned settlement context for in-transaction pin/check
     * discipline. Captures immutable journal/entries/receipts/consent
     * bytes; callers compare exact bytes after final SQL/trigger effects.
     * Null means no migration. Never an authority token.
     */
    internal data class PinnedSettled(
        val journal: SyncLegacyImportEntity,
        val entries: List<SyncLegacyImportEntryEntity>,
        val acceptances: List<SyncLegacyImportAcceptanceEntity>,
        val progressRevision: Long,
        val progressGeneration: Long,
        val progressPhase: String,
        val progressOperationId: String?,
        val progressConsent: String?,
    ) {
        override fun toString(): String = "LegacyImportSettlement.PinnedSettled(redacted)"
    }

    /**
     * Pins the definitive settlement context. Must run inside the caller's
     * Room transaction for combined publication/completion paths (wraps its
     * reads in a transaction that joins an enclosing one). Returns null
     * when no migration exists; throws on unsettled/corrupt evidence.
     * Cancellation propagates.
     */
    suspend fun pinSettled(database: GymDatabase): PinnedSettled? {
        return database.withTransaction {
            currentCoroutineContext().ensureActive()
            val journal = readJournalStrict(database)
            val entries = readEntriesStrict(database)
            if (journal == null) {
                if (entries.isNotEmpty() ||
                    readProgressStrict(database) != null ||
                    readAcceptancesStrict(database).isNotEmpty()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                currentCoroutineContext().ensureActive()
                null
            } else {
                if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val target = parseTargetStrict(journal)
                val current = currentAuthority(database)
                val view = validateView(database, journal, entries, target, current)
                if (view.acceptedPrefix != entries.size) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                currentCoroutineContext().ensureActive()
                PinnedSettled(
                    journal = journal,
                    entries = entries,
                    acceptances = view.acceptances,
                    progressRevision = view.progress.roomRevision,
                    progressGeneration = view.progress.roomSessionGeneration,
                    progressPhase = view.progress.roomPhase,
                    progressOperationId = view.progress.operationId,
                    progressConsent = view.progress.activeConsentMutationId,
                )
            }
        }
    }

    /**
     * Revalidates pinned bytes after final SQL/trigger effects inside the
     * same transaction: journal/entries/prior receipts and consent must be
     * byte-identical (source/entries/prior receipts never drift while the
     * transaction commits). Null pins require continued absence.
     * Cancellation propagates.
     */
    suspend fun verifyPinnedUnchanged(database: GymDatabase, pinned: PinnedSettled?) {
        database.withTransaction {
            currentCoroutineContext().ensureActive()
            val journal = readJournalStrict(database)
            val entries = readEntriesStrict(database)
            if (pinned == null) {
                if (journal != null || entries.isNotEmpty() ||
                    readProgressStrict(database) != null ||
                    readAcceptancesStrict(database).isNotEmpty()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                currentCoroutineContext().ensureActive()
                return@withTransaction
            }
            if (journal != pinned.journal) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (entries != pinned.entries) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val acceptances = readAcceptancesStrict(database)
            if (acceptances != pinned.acceptances) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val progress = readProgressStrict(database)
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (progress.roomRevision != pinned.progressRevision ||
                progress.roomSessionGeneration != pinned.progressGeneration ||
                progress.roomPhase != pinned.progressPhase ||
                progress.operationId != pinned.progressOperationId ||
                progress.activeConsentMutationId != pinned.progressConsent
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * Post-completion revalidation for the caller-owned activation
     * transition. Immutable journal/entries/receipts/consent must be
     * byte-identical to the pin; the progress anchor may advance ONLY by
     * the exact completion transition (newRevision, same generation,
     * active phase, same operation), and the actual current authority must
     * be ACTIVE at that tuple with the same origin/account binding.
     * Anything else fails closed. Cancellation propagates.
     */
    suspend fun verifyPinnedAdvancedForCompletion(
        database: GymDatabase,
        pinned: PinnedSettled?,
        newRevision: Long,
    ) {
        database.withTransaction {
            currentCoroutineContext().ensureActive()
            val journal = readJournalStrict(database)
            val entries = readEntriesStrict(database)
            if (pinned == null) {
                if (journal != null || entries.isNotEmpty() ||
                    readProgressStrict(database) != null ||
                    readAcceptancesStrict(database).isNotEmpty()
                ) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                currentCoroutineContext().ensureActive()
                return@withTransaction
            }
            if (journal != pinned.journal) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (entries != pinned.entries) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val acceptances = readAcceptancesStrict(database)
            if (acceptances != pinned.acceptances) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val progress = readProgressStrict(database)
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (progress.roomRevision != newRevision ||
                progress.roomSessionGeneration != pinned.progressGeneration ||
                progress.roomPhase != "active" ||
                progress.operationId != pinned.progressOperationId ||
                progress.activeConsentMutationId != pinned.progressConsent
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val current = currentAuthority(database)
            if (current.phase != RecoveryRoomPhase.ACTIVE ||
                current.revision != newRevision ||
                current.sessionGeneration != pinned.progressGeneration ||
                current.cachedAccountId != pinned.journal.accountId
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val identity = current.identity
                ?: throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            if (identity.operationId != pinned.progressOperationId ||
                identity.binding.sessionGeneration != pinned.progressGeneration ||
                identity.binding.accountId != pinned.journal.accountId
            ) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            val journalOrigin = try {
                LegacyImportSource.parseCanonicalOrigin(pinned.journal.serviceOrigin)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            if (identity.binding.origin != journalOrigin) {
                throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
            }
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * Definitive-settlement gate for empty-queue and completion paths. A
     * missing journal requires every orphan (entries/progress/receipts) to
     * be absent; a retained journal requires full validation with the
     * current anchor and a complete receipt prefix. Cancellation
     * propagates; every other failure is closed corruption.
     */
    suspend fun requireSettled(database: GymDatabase) {
        try {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                val journal = readJournalStrict(database)
                val entries = readEntriesStrict(database)
                if (journal == null) {
                    if (entries.isNotEmpty() ||
                        readProgressStrict(database) != null ||
                        readAcceptancesStrict(database).isNotEmpty()
                    ) {
                        throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                    }
                    return@withTransaction
                }
                if (journal.phase == LegacyImportSource.PHASE_BLOCKED) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
                val target = parseTargetStrict(journal)
                val current = currentAuthority(database)
                val view = validateView(database, journal, entries, target, current)
                if (view.acceptedPrefix != entries.size) {
                    throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LegacyImportException) {
            throw e
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        }
    }

    suspend fun currentAuthority(database: GymDatabase): RecoveryRoomSnapshot {
        try {
            currentCoroutineContext().ensureActive()
            return RoomSessionAuthority(database).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityException) {
            throw LegacyImportException(LegacyImportFailure.CORRUPT_EVIDENCE)
        } catch (_: Exception) {
            throw LegacyImportException(LegacyImportFailure.UNAVAILABLE)
        }
    }

    override fun toString(): String = "LegacyImportSettlement(redacted)"
}
