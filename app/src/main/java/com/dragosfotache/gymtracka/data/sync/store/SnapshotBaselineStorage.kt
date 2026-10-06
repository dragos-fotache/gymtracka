package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncSnapshotRecordEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel

/**
 * Focused complete-baseline helpers. All retained-evidence failures use the
 * existing fixed safe errors; incoming staged-model failures propagate the
 * existing [SyncModelException]. Caller cancellation is never wrapped.
 */
internal data class StagedBaselineUpsert(
    val key: Pair<String, String>,
    val change: SyncChangeModel,
    val record: CanonicalRecord,
    val json: String,
)

/**
 * Deep-decodes every staged whole canonical upsert with the existing strict
 * record codecs and binds each row to its exact retained body. Staged bodies
 * are upserts only; row/type/ID/revision disagreement or a repeated staged
 * root fails with the existing safe model error. Never suspends.
 */
internal fun decodeStagedBaseline(records: List<SyncSnapshotRecordEntity>): List<StagedBaselineUpsert> {
    val staged = ArrayList<StagedBaselineUpsert>(records.size)
    val seen = HashSet<Pair<String, String>>()
    for (row in records) {
        val type = AggregateType.fromWire(row.aggregateType)
        val decoded = decodeStoredRecord(type, row.canonicalJson)
        if (decoded.aggregateType != type ||
            decoded.aggregateId != row.aggregateId ||
            decoded.revision != row.serverRevision
        ) {
            throw SyncModelException("Snapshot record does not match its staged body")
        }
        val key = type.wireValue to row.aggregateId
        if (!seen.add(key)) {
            throw SyncModelException("Snapshot stage repeats a staged aggregate")
        }
        val change = SyncChangeModel(
            aggregateType = type,
            aggregateId = row.aggregateId,
            operation = ChangeOperation.UPSERT,
            revision = row.serverRevision,
            record = decoded,
        )
        staged.add(StagedBaselineUpsert(key, change, decoded, row.canonicalJson))
    }
    return staged
}

/**
 * Validates the COMPLETE staged frozen graph, not each page's graph and not a
 * union with old missing roots. Later-page custom dependencies resolve because
 * the whole staged set is validated together; an empty staged graph is valid.
 * Bundled references must exist as immutable global nondeleted catalogue rows,
 * relationship lookup IDs must exist, and at most one workout may be active.
 * Per-aggregate shape invariants already ran in the strict codecs. Must run
 * inside the caller's active Room transaction; never writes.
 */
internal suspend fun validateCompleteBaselineGraph(
    database: GymDatabase,
    staged: List<StagedBaselineUpsert>,
) {
    if (!database.inTransaction()) {
        throw IllegalStateException("Snapshot baseline requires an active transaction")
    }
    val workouts = ArrayList<WorkoutRecordModel>()
    val routines = ArrayList<RoutineRecordModel>()
    val customs = ArrayList<CustomExerciseRecordModel>()
    for (item in staged) {
        when (val record = item.record) {
            is CanonicalRecord.Workout -> workouts.add(record.record)
            is CanonicalRecord.Routine -> routines.add(record.record)
            is CanonicalRecord.CustomExercise -> customs.add(record.record)
        }
    }
    val live = LiveProjectionGraph(
        workouts = workouts,
        routines = routines,
        customs = customs,
        customIds = customs.map { it.id }.toSet(),
    )
    val projectionDao = database.syncProjectionDao()
    val exercisesById = projectionDao.getAllExercises().associateBy { it.id }
    val muscleIds = projectionDao.getMuscleGroupIds().toSet()
    val equipmentIds = projectionDao.getEquipmentIds().toSet()
    validateCanonicalGraphDependencies(live, exercisesById, muscleIds, equipmentIds)
}

/**
 * Baseline revision arbitration against retained observation. A staged revision
 * LOWER than an already observed retained revision fails closed; an EQUAL
 * revision must agree on authoritative fields with a retained upsert (only
 * convenience exerciseName and unrepresented additive differences are ignored)
 * and never restores a delete or snapshot_absent; a HIGHER revision replaces
 * subject to the family rules checked separately. Every staged member that
 * survives arbitration installs; none is silently ignored. Never suspends.
 */
internal fun arbitrateBaselineRevisions(
    retained: ValidatedRetained,
    staged: List<StagedBaselineUpsert>,
) {
    for (item in staged) {
        val existing = retained.byKey[item.key] ?: continue
        if (existing.canonicalState == "unobserved") {
            continue
        }
        val existingRevision = existing.serverRevision ?: 0L
        if (item.change.revision < existingRevision) {
            throw CanonicalConflictException()
        }
        if (item.change.revision == existingRevision) {
            if (existing.canonicalState != "upsert") {
                throw CanonicalConflictException()
            }
            val stored = retained.upsertsByKey[item.key]
                ?: throw CanonicalStorageException()
            if (!canonicalRecordsEqualAuthoritative(stored, item.record)) {
                throw CanonicalConflictException()
            }
        }
    }
}
