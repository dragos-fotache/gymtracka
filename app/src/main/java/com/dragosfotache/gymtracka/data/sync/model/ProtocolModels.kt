package com.dragosfotache.gymtracka.data.sync.model

/**
 * Protocol envelope models for canonical changes, batches, and push/pull/snapshot
 * results. Wire strings match OpenAPI exactly; no enum ordinals or Kotlin enum
 * names leak onto the wire. Timestamps inside records reuse the aggregate codecs.
 */

/** Explicit OpenAPI AggregateType wire strings as a typed discriminator. */
enum class AggregateType(val wireValue: String) {
    WORKOUT("workout"),
    ROUTINE("routine"),
    CUSTOM_EXERCISE("customExercise"),
    ;

    companion object {
        fun fromWire(wireValue: String): AggregateType =
            entries.singleOrNull { it.wireValue == wireValue }
                ?: throw SyncModelException("Unknown aggregate type: $wireValue")
    }
}

/** Explicit OpenAPI SyncChange operation wire strings as a typed discriminator. */
enum class ChangeOperation(val wireValue: String) {
    UPSERT("upsert"),
    DELETE("delete"),
    ;

    companion object {
        fun fromWire(wireValue: String): ChangeOperation =
            entries.singleOrNull { it.wireValue == wireValue }
                ?: throw SyncModelException("Unknown change operation: $wireValue")
    }
}

/** Explicit OpenAPI push-outcome status wire strings as a typed discriminator. */
enum class MutationStatus(val wireValue: String) {
    ACCEPTED("accepted"),
    DUPLICATE("duplicate"),
    REJECTED("rejected"),
    ;

    companion object {
        fun fromWire(wireValue: String): MutationStatus =
            entries.singleOrNull { it.wireValue == wireValue }
                ?: throw SyncModelException("Unknown mutation status: $wireValue")
    }
}

/** Typed canonical record union. Never a generic map, Room entity, or raw JSON. */
sealed class CanonicalRecord {
    abstract val aggregateId: String
    abstract val revision: Long
    abstract val aggregateType: AggregateType

    data class Workout(val record: WorkoutRecordModel) : CanonicalRecord() {
        override val aggregateId: String get() = record.id
        override val revision: Long get() = record.revision
        override val aggregateType: AggregateType get() = AggregateType.WORKOUT
    }

    data class Routine(val record: RoutineRecordModel) : CanonicalRecord() {
        override val aggregateId: String get() = record.id
        override val revision: Long get() = record.revision
        override val aggregateType: AggregateType get() = AggregateType.ROUTINE
    }

    data class CustomExercise(val record: CustomExerciseRecordModel) : CanonicalRecord() {
        override val aggregateId: String get() = record.id
        override val revision: Long get() = record.revision
        override val aggregateType: AggregateType get() = AggregateType.CUSTOM_EXERCISE
    }
}

/** One ordered account change. Mirrors OpenAPI SyncChange. */
data class SyncChangeModel(
    val aggregateType: AggregateType,
    val aggregateId: String,
    val operation: ChangeOperation,
    val revision: Long,
    val record: CanonicalRecord?,
)

/** One indivisible commit batch. Mirrors OpenAPI SyncBatch. */
data class SyncBatchModel(
    val batchId: String,
    val cursor: String,
    val changes: List<SyncChangeModel>,
)

/** Per-mutation push outcome. Mirrors the OpenAPI PushResult result entries. */
data class MutationOutcomeModel(
    val mutationId: String,
    val status: MutationStatus,
    val errorCode: String?,
    val batch: SyncBatchModel?,
)

/** Push response. Mirrors OpenAPI PushResult. */
data class PushResultModel(
    val results: List<MutationOutcomeModel>,
)

/** Incremental pull page. Mirrors OpenAPI PullResult. */
data class PullResultModel(
    val batches: List<SyncBatchModel>,
    val nextCursor: String,
    val hasMore: Boolean,
)

/** Full-resync snapshot page. Mirrors OpenAPI SnapshotResult. */
data class SnapshotResultModel(
    val records: List<SyncChangeModel>,
    val baselineCursor: String,
    val nextPageToken: String?,
    val hasMore: Boolean,
)
