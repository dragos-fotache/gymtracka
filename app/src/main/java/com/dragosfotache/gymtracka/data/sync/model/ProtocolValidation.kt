package com.dragosfotache.gymtracka.data.sync.model

/**
 * Envelope invariants for canonical changes, batches, and result pages.
 * Cursors stay opaque: they are checked for presence only, never parsed, sorted,
 * or compared lexically. Server order is always preserved.
 */

fun validateSyncChange(change: SyncChangeModel): SyncChangeModel {
    requireWorkoutUuid(change.aggregateId, "aggregateId")
    requirePositiveRevision(change.revision, "Change")
    val record = change.record
    if (change.operation == ChangeOperation.UPSERT) {
        if (record == null) throw SyncModelException("Upsert change requires a record")
        if (record.aggregateType != change.aggregateType) {
            throw SyncModelException("Change record type does not match aggregate type")
        }
        if (record.aggregateId != change.aggregateId) {
            throw SyncModelException("Change record ID does not match aggregate ID")
        }
        if (record.revision != change.revision) {
            throw SyncModelException("Change record revision does not match change revision")
        }
        // Directly constructed records bypass JSON decoding, so the full aggregate
        // shape validators run here too; the normalized record is returned.
        val validated = when (record) {
            is CanonicalRecord.Workout -> record.copy(record = validateWorkoutRecordModel(record.record))
            is CanonicalRecord.Routine -> record.copy(record = validateRoutineRecordModel(record.record))
            is CanonicalRecord.CustomExercise ->
                record.copy(record = validateCustomExerciseRecordModel(record.record))
        }
        return change.copy(record = validated)
    } else if (record != null) {
        throw SyncModelException("Delete change must not carry a record")
    }
    return change
}

fun validateSyncBatch(batch: SyncBatchModel): SyncBatchModel {
    requireWorkoutUuid(batch.batchId, "batchId")
    if (batch.cursor.isEmpty()) throw SyncModelException("Batch cursor must be nonempty")
    if (batch.changes.isEmpty()) throw SyncModelException("Batch must contain changes")
    val keys = batch.changes.map { it.aggregateType to it.aggregateId }
    if (keys.toSet().size != keys.size) {
        throw SyncModelException("Batch contains a duplicate aggregate")
    }
    return batch.copy(changes = batch.changes.map(::validateSyncChange))
}

fun validateMutationOutcome(outcome: MutationOutcomeModel): MutationOutcomeModel {
    requireWorkoutUuid(outcome.mutationId, "mutationId")
    val batch = outcome.batch
    val errorCode = outcome.errorCode
    when (outcome.status) {
        MutationStatus.ACCEPTED -> {
            if (batch == null) throw SyncModelException("Accepted outcome requires a batch")
            if (errorCode != null) throw SyncModelException("Accepted outcome must not carry an error code")
        }
        MutationStatus.REJECTED -> {
            if (errorCode.isNullOrBlank()) throw SyncModelException("Rejected outcome requires an error code")
            if (batch != null) throw SyncModelException("Rejected outcome must not carry a batch")
        }
        MutationStatus.DUPLICATE -> {
            val replayedBatch = batch != null && errorCode == null
            val replayedRejection = batch == null && !errorCode.isNullOrBlank()
            if (!replayedBatch && !replayedRejection) {
                throw SyncModelException("Duplicate outcome must replay exactly one original result")
            }
        }
    }
    return outcome.copy(batch = batch?.let(::validateSyncBatch))
}

fun validatePushResult(result: PushResultModel): PushResultModel {
    val ids = result.results.map { it.mutationId }
    if (ids.toSet().size != ids.size) throw SyncModelException("Push result contains duplicate outcome IDs")
    return result.copy(results = result.results.map(::validateMutationOutcome))
}

fun validatePullResult(result: PullResultModel): PullResultModel {
    if (result.nextCursor.isEmpty()) throw SyncModelException("Pull result requires a next cursor")
    val batches = result.batches.map(::validateSyncBatch)
    val batchIds = batches.map { it.batchId }
    if (batchIds.toSet().size != batchIds.size) {
        throw SyncModelException("Pull page contains repeated batch IDs")
    }
    val cursors = batches.map { it.cursor }
    if (cursors.toSet().size != cursors.size) {
        throw SyncModelException("Pull page contains repeated cursors")
    }
    if (batches.isEmpty()) {
        if (result.hasMore) throw SyncModelException("Empty pull page must not claim more data")
    } else if (result.nextCursor != batches.last().cursor) {
        throw SyncModelException("Pull next cursor must equal the last batch cursor")
    }
    return result.copy(batches = batches)
}

fun validateSnapshotResult(result: SnapshotResultModel): SnapshotResultModel {
    if (result.baselineCursor.isEmpty()) throw SyncModelException("Snapshot result requires a baseline cursor")
    val records = result.records.map(::validateSyncChange)
    records.forEach {
        if (it.operation != ChangeOperation.UPSERT) {
            throw SyncModelException("Snapshot records are non-deleted upserts only")
        }
    }
    val keys = records.map { it.aggregateType to it.aggregateId }
    if (keys.toSet().size != keys.size) {
        throw SyncModelException("Snapshot page contains a duplicate aggregate")
    }
    val token = result.nextPageToken
    if (result.hasMore) {
        if (token.isNullOrBlank()) throw SyncModelException("Snapshot hasMore requires a next page token")
    } else if (token != null) {
        throw SyncModelException("Terminal snapshot page must not carry a page token")
    }
    return result.copy(records = records)
}
