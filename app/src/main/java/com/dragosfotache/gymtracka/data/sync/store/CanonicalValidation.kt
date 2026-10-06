package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncCanonicalRootEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodeCustomExerciseRecord
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineRecord
import com.dragosfotache.gymtracka.data.sync.codec.decodeSyncBatch
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeCanonicalRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeSyncBatch
import com.dragosfotache.gymtracka.data.sync.codec.parseServiceResponseObject
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.session.PreparedSessionEnginePermit
import com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthority

/**
 * Focused canonical validation helpers. All retained-evidence failures use fixed safe
 * messages without stored JSON or domain values. Incoming model failures propagate
 * the existing [SyncModelException]. Caller cancellation is never wrapped.
 */
internal fun ownedCanonicalBatch(batch: SyncBatchModel): SyncBatchModel =
    decodeSyncBatch(encodeSyncBatch(batch))

internal suspend fun checkCanonicalFences(
    database: GymDatabase,
    accountId: String,
    sessionGeneration: Long,
    expectedCacheEpoch: Long,
    permit: PreparedSessionEnginePermit? = null,
): Long {
    if (permit != null) {
        if (permit.database !== database ||
            permit.accountId != accountId ||
            permit.sessionGeneration != sessionGeneration
        ) {
            throw IllegalStateException("Prepared permit does not match this store")
        }
        RoomSessionAuthority(database).checkPreparedInTransaction(permit)
    } else {
        val device = database.syncDao().getDeviceState()
        val actualAccountId = device?.activeAccountId
        val actualGeneration = device?.sessionGeneration ?: 0L
        if (actualAccountId != accountId || actualGeneration != sessionGeneration) {
            throw StaleSessionException(
                accountId,
                sessionGeneration,
                actualAccountId,
                actualGeneration,
            )
        }
    }
    val cache = database.syncCanonicalDao().getAccountCache(accountId)
    if (cache != null) {
        if (cache.cacheEpoch < 0 || cache.materializedEpoch < 0) {
            throw CanonicalStorageException()
        }
        if (cache.materializedEpoch > cache.cacheEpoch) {
            throw CanonicalStorageException()
        }
    }
    val actualEpoch = cache?.cacheEpoch ?: 0L
    if (actualEpoch < 0) {
        throw CanonicalStorageException()
    }
    if (actualEpoch != expectedCacheEpoch) {
        throw StaleCanonicalEpochException()
    }
    return actualEpoch
}

internal fun decodeStoredRecord(type: AggregateType, json: String): CanonicalRecord {
    val obj = parseServiceResponseObject(json)
    return when (type) {
        AggregateType.WORKOUT -> CanonicalRecord.Workout(decodeWorkoutRecord(obj))
        AggregateType.ROUTINE -> CanonicalRecord.Routine(decodeRoutineRecord(obj))
        AggregateType.CUSTOM_EXERCISE -> CanonicalRecord.CustomExercise(decodeCustomExerciseRecord(obj))
    }
}

internal fun encodeCanonicalJson(record: CanonicalRecord): String =
    encodeCanonicalRecord(record).toString()

internal data class CanonicalChangeDecision(val change: SyncChangeModel, val selected: Boolean)

/**
 * Revision arbitration shared by direct and composed transactions. Lower revisions
 * are no-ops, higher revisions select the whole new operation/record, and equal
 * revisions require DELETE/DELETE agreement or authoritative UPSERT equality.
 * Snapshot absence never restores on lower/equal observations. Any equal-revision
 * operation disagreement or authoritative difference rejects the whole batch.
 */
internal fun selectCanonicalChanges(
    changes: List<SyncChangeModel>,
    retained: ValidatedRetained,
): List<CanonicalChangeDecision> {
    val decisions = ArrayList<CanonicalChangeDecision>(changes.size)
    for (change in changes) {
        val key = change.aggregateType.wireValue to change.aggregateId
        val existing = retained.byKey[key]
        if (existing == null || existing.canonicalState == "unobserved") {
            decisions.add(CanonicalChangeDecision(change, selected = true))
            continue
        }
        val existingRevision = existing.serverRevision ?: 0L
        if (change.revision < existingRevision) {
            decisions.add(CanonicalChangeDecision(change, selected = false))
            continue
        }
        if (change.revision > existingRevision) {
            decisions.add(CanonicalChangeDecision(change, selected = true))
            continue
        }
        when (existing.canonicalState) {
            "snapshot_absent" -> {
                decisions.add(CanonicalChangeDecision(change, selected = false))
            }
            "delete" -> {
                if (change.operation == com.dragosfotache.gymtracka.data.sync.model.ChangeOperation.DELETE) {
                    decisions.add(CanonicalChangeDecision(change, selected = false))
                } else {
                    throw CanonicalConflictException()
                }
            }
            "upsert" -> {
                if (change.operation != com.dragosfotache.gymtracka.data.sync.model.ChangeOperation.UPSERT) {
                    throw CanonicalConflictException()
                }
                val incomingRecord = change.record as CanonicalRecord
                val storedRecord = retained.upsertsByKey[key]
                    ?: throw CanonicalStorageException()
                val equal = canonicalRecordsEqualAuthoritative(storedRecord, incomingRecord)
                if (equal) {
                    decisions.add(CanonicalChangeDecision(change, selected = false))
                } else {
                    throw CanonicalConflictException()
                }
            }
            else -> throw CanonicalStorageException()
        }
    }
    return decisions
}

internal data class ValidatedRetained(
    val rows: List<SyncCanonicalRootEntity>,
    val byKey: Map<Pair<String, String>, SyncCanonicalRootEntity>,
    val upsertsByKey: Map<Pair<String, String>, CanonicalRecord>,
)

internal suspend fun loadValidatedRetained(
    database: GymDatabase,
    accountId: String,
    accountEpoch: Long,
): ValidatedRetained {
    val rows = database.syncCanonicalDao().getRootsForAccount(accountId)
    val upserts = mutableMapOf<Pair<String, String>, CanonicalRecord>()
    for (row in rows) {
        val type = try {
            AggregateType.fromWire(row.aggregateType)
        } catch (_: SyncModelException) {
            throw CanonicalStorageException()
        }
        try {
            requireWorkoutUuid(row.aggregateId, "aggregateId")
        } catch (_: SyncModelException) {
            throw CanonicalStorageException()
        }
        if (row.localGenerationWatermark < 0 || row.cacheEpoch < 0) {
            throw CanonicalStorageException()
        }
        if (row.cacheEpoch > accountEpoch) {
            throw CanonicalStorageException()
        }
        when (row.canonicalState) {
            "unobserved" -> {
                if (row.serverRevision != null || row.canonicalJson != null) {
                    throw CanonicalStorageException()
                }
            }
            "upsert" -> {
                val revision = row.serverRevision
                val json = row.canonicalJson
                if (revision == null || revision < 1 || json == null) {
                    throw CanonicalStorageException()
                }
                val record = try {
                    decodeStoredRecord(type, json)
                } catch (_: SyncModelException) {
                    throw CanonicalStorageException()
                }
                if (record.aggregateType != type ||
                    record.aggregateId != row.aggregateId ||
                    record.revision != revision
                ) {
                    throw CanonicalStorageException()
                }
                upserts[type.wireValue to row.aggregateId] = record
            }
            "delete" -> {
                if (row.serverRevision == null ||
                    row.serverRevision < 1 ||
                    row.canonicalJson != null
                ) {
                    throw CanonicalStorageException()
                }
            }
            "snapshot_absent" -> {
                if (row.canonicalJson != null) {
                    throw CanonicalStorageException()
                }
                val revision = row.serverRevision
                if (revision != null && revision < 1) {
                    throw CanonicalStorageException()
                }
            }
            else -> throw CanonicalStorageException()
        }
    }
    return ValidatedRetained(
        rows = rows,
        byKey = rows.associateBy { it.aggregateType to it.aggregateId },
        upsertsByKey = upserts,
    )
}

private fun workoutEqualIgnoringNames(a: WorkoutRecordModel, b: WorkoutRecordModel): Boolean {
    if (a.id != b.id ||
        a.name != b.name ||
        a.status != b.status ||
        a.startedAtMillis != b.startedAtMillis ||
        a.finishedAtMillis != b.finishedAtMillis ||
        a.revision != b.revision ||
        a.exercises.size != b.exercises.size
    ) {
        return false
    }
    for (index in a.exercises.indices) {
        val entryA = a.exercises[index]
        val entryB = b.exercises[index]
        if (entryA.id != entryB.id ||
            entryA.exerciseId != entryB.exerciseId ||
            entryA.position != entryB.position ||
            entryA.notes != entryB.notes ||
            entryA.restDurationSeconds != entryB.restDurationSeconds ||
            entryA.sets.size != entryB.sets.size
        ) {
            return false
        }
        for (setIndex in entryA.sets.indices) {
            if (entryA.sets[setIndex] != entryB.sets[setIndex]) {
                return false
            }
        }
    }
    return true
}

private fun routineEqualIgnoringNames(a: RoutineRecordModel, b: RoutineRecordModel): Boolean {
    if (a.id != b.id ||
        a.name != b.name ||
        a.isDraft != b.isDraft ||
        a.revision != b.revision ||
        a.exercises.size != b.exercises.size
    ) {
        return false
    }
    for (index in a.exercises.indices) {
        val entryA = a.exercises[index]
        val entryB = b.exercises[index]
        if (entryA.id != entryB.id ||
            entryA.exerciseId != entryB.exerciseId ||
            entryA.position != entryB.position ||
            entryA.restDurationSeconds != entryB.restDurationSeconds ||
            entryA.sets.size != entryB.sets.size
        ) {
            return false
        }
        for (setIndex in entryA.sets.indices) {
            if (entryA.sets[setIndex] != entryB.sets[setIndex]) {
                return false
            }
        }
    }
    return true
}

internal fun canonicalRecordsEqualAuthoritative(
    existing: CanonicalRecord,
    incoming: CanonicalRecord,
): Boolean {
    if (existing.aggregateType != incoming.aggregateType) {
        return false
    }
    return when {
        existing is CanonicalRecord.Workout && incoming is CanonicalRecord.Workout ->
            workoutEqualIgnoringNames(existing.record, incoming.record)
        existing is CanonicalRecord.Routine && incoming is CanonicalRecord.Routine ->
            routineEqualIgnoringNames(existing.record, incoming.record)
        existing is CanonicalRecord.CustomExercise && incoming is CanonicalRecord.CustomExercise ->
            existing.record == incoming.record
        else -> false
    }
}

internal enum class FamilyRole {
    ROOT,
    ENTRY,
    SET,
}

internal data class FamilyBinding(
    val family: AggregateType,
    val rootId: String,
    val entryId: String?,
    val role: FamilyRole,
)

private fun checkBinding(
    bindings: MutableMap<String, FamilyBinding>,
    uuid: String,
    binding: FamilyBinding,
) {
    val known = bindings[uuid]
    if (known == null) {
        bindings[uuid] = binding
        return
    }
    if (known != binding) {
        throw CanonicalConflictException()
    }
}

/**
 * Rejects incompatible UUID reuse within one workout/routine family, scoped to the
 * current account. ROOT roles reserve from every retained ledger row
 * (unobserved/delete/snapshot_absent included) and every selected incoming
 * workout/routine change (DELETE included); prior upsert child bindings and
 * selected upsert children compare against those reservations. A
 * tombstoned/reserved/absent root UUID must not become a child, and an unknown
 * incoming DELETE root UUID must not claim an existing child role. Always
 * validates retained bindings, even for an all-no-op batch; inconsistent retained
 * bindings fail closed. Workout and routine namespaces are independent; custom
 * exerciseId references never participate.
 */
internal fun checkFamilyBindings(
    retained: ValidatedRetained,
    selected: List<SyncChangeModel>,
) {
    val workoutBindings = mutableMapOf<String, FamilyBinding>()
    val routineBindings = mutableMapOf<String, FamilyBinding>()
    for (row in retained.rows) {
        val family = AggregateType.fromWire(row.aggregateType)
        when (family) {
            AggregateType.WORKOUT -> checkBinding(
                workoutBindings,
                row.aggregateId,
                FamilyBinding(AggregateType.WORKOUT, row.aggregateId, null, FamilyRole.ROOT),
            )
            AggregateType.ROUTINE -> checkBinding(
                routineBindings,
                row.aggregateId,
                FamilyBinding(AggregateType.ROUTINE, row.aggregateId, null, FamilyRole.ROOT),
            )
            AggregateType.CUSTOM_EXERCISE -> Unit
        }
    }
    for ((_, record) in retained.upsertsByKey) {
        when (record) {
            is CanonicalRecord.Workout -> {
                val model = record.record
                for (entry in model.exercises) {
                    checkBinding(
                        workoutBindings,
                        entry.id,
                        FamilyBinding(AggregateType.WORKOUT, model.id, null, FamilyRole.ENTRY),
                    )
                    for (set in entry.sets) {
                        checkBinding(
                            workoutBindings,
                            set.id,
                            FamilyBinding(AggregateType.WORKOUT, model.id, entry.id, FamilyRole.SET),
                        )
                    }
                }
            }
            is CanonicalRecord.Routine -> {
                val model = record.record
                for (entry in model.exercises) {
                    checkBinding(
                        routineBindings,
                        entry.id,
                        FamilyBinding(AggregateType.ROUTINE, model.id, null, FamilyRole.ENTRY),
                    )
                    for (set in entry.sets) {
                        checkBinding(
                            routineBindings,
                            set.id,
                            FamilyBinding(AggregateType.ROUTINE, model.id, entry.id, FamilyRole.SET),
                        )
                    }
                }
            }
            is CanonicalRecord.CustomExercise -> Unit
        }
    }
    for (change in selected) {
        when (change.aggregateType) {
            AggregateType.WORKOUT -> checkBinding(
                workoutBindings,
                change.aggregateId,
                FamilyBinding(AggregateType.WORKOUT, change.aggregateId, null, FamilyRole.ROOT),
            )
            AggregateType.ROUTINE -> checkBinding(
                routineBindings,
                change.aggregateId,
                FamilyBinding(AggregateType.ROUTINE, change.aggregateId, null, FamilyRole.ROOT),
            )
            AggregateType.CUSTOM_EXERCISE -> Unit
        }
    }
    for (change in selected) {
        val record = change.record ?: continue
        when (record) {
            is CanonicalRecord.Workout -> {
                val model = record.record
                checkBinding(
                    workoutBindings,
                    model.id,
                    FamilyBinding(AggregateType.WORKOUT, model.id, null, FamilyRole.ROOT),
                )
                for (entry in model.exercises) {
                    checkBinding(
                        workoutBindings,
                        entry.id,
                        FamilyBinding(AggregateType.WORKOUT, model.id, null, FamilyRole.ENTRY),
                    )
                    for (set in entry.sets) {
                        checkBinding(
                            workoutBindings,
                            set.id,
                            FamilyBinding(AggregateType.WORKOUT, model.id, entry.id, FamilyRole.SET),
                        )
                    }
                }
            }
            is CanonicalRecord.Routine -> {
                val model = record.record
                checkBinding(
                    routineBindings,
                    model.id,
                    FamilyBinding(AggregateType.ROUTINE, model.id, null, FamilyRole.ROOT),
                )
                for (entry in model.exercises) {
                    checkBinding(
                        routineBindings,
                        entry.id,
                        FamilyBinding(AggregateType.ROUTINE, model.id, null, FamilyRole.ENTRY),
                    )
                    for (set in entry.sets) {
                        checkBinding(
                            routineBindings,
                            set.id,
                            FamilyBinding(AggregateType.ROUTINE, model.id, entry.id, FamilyRole.SET),
                        )
                    }
                }
            }
            is CanonicalRecord.CustomExercise -> Unit
        }
    }
}
