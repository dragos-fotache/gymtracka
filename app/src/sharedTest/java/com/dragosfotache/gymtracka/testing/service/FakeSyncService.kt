package com.dragosfotache.gymtracka.testing.service

import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.validatePushResult
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.validateSyncBatch
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutRecordModel
import java.util.UUID

/**
 * Test-only deterministic fake sync service shared by JVM and instrumentation
 * tests. It is never packaged into production and contains no Room or Android
 * classes.
 *
 * Assignment 016 (M4b) implements the seven basic aggregate commands:
 * `workout.start`, `workout.upsert`, `routine.upsert`, `routine.save`,
 * `customExercise.upsert`, `aggregate.import`, and `aggregate.delete`.
 * Assignment 017a adds `workout.finish`. Assignment 017b1 adds
 * `workout.performAgain`. Assignment 017b2 adds `routine.startWorkout` and
 * `workout.saveAsRoutine`. Assignment 017c adds `workout.addExercises`.
 * Assignment 017d adds `workout.replaceExercise`, `workout.deleteExercise`,
 * `workout.deleteSet`, `routine.deleteExercise`, and `routine.deleteSet`;
 * every typed semantic command is now implemented. `pull`/`snapshot` stay explicit
 * [UnsupportedOperationException] stubs on the account adapter, and mutation
 * deduplication replay stays Assignment 018 (outcomes are recorded but a
 * repeated mutation ID re-executes; no stable retry semantics are claimed).
 *
 * Execution rules, per the backend contract:
 *
 * - Owner identity derives solely from the account-bound adapter. Payloads
 *   carry no owner fields.
 * - Root identity is `(aggregateType, id)` globally across accounts. Roots of
 *   different types may share a UUID; the same type plus ID may never be
 *   owned twice. Tombstones retain ownership, revision, and child-collision
 *   evidence so deleted IDs cannot be silently reparented or reused.
 * - Bundled exercise, muscle, and equipment references live in separate
 *   seeded namespaces. A muscle/equipment UUID is never a valid exercise
 *   reference. The bundled catalogue is read-only.
 * - Canonical display names resolve from the current seeded catalogue or the
 *   caller's current live custom records. Upload inputs carry no authoritative
 *   names and callers never supply revisions.
 * - Each mutation is staged fully before any state changes: validation reads
 *   only, then one validated commit publishes roots, child ownership,
 *   revisions, and the account change log. A rejected mutation leaves roots,
 *   children, ownership, revision counters, and logs untouched.
 * - `workout.upsert` replaces the complete live ACTIVE aggregate: name,
 *   start time, notes, configured and recorded rest, nullable inputs, and
 *   ordered children with stable IDs. Finished history is immutable and the
 *   known revision is never a precondition.
 * - Server commit order assigns increasing per-root revisions. Client
 *   timestamps, generations, and known revisions never choose winners.
 * - A sole-active replacement (start or active import while another active
 *   workout exists) commits one batch containing the old delete followed by
 *   the new upsert.
 *
 * Permanent per-mutation error codes: [OWNED_ROOT_MISSING] (unknown, foreign,
 * or revivable-deleted target through one non-disclosing code),
 * [ROOT_ALREADY_EXISTS] (create-only collision, including tombstones and
 * foreign roots), [IMMUTABLE_FINISHED] (upsert against finished history),
 * [INVALID_REFERENCE] (unknown/cross-namespace/cross-owner exercise, muscle,
 * or equipment reference, including bundled-UUID mutation attempts),
 * [CHILD_COLLISION] (child ID owned elsewhere, retained by a tombstone,
 * reparented across exercises, or reused across root/child levels),
 * [INVALID_ROUTINE_STATE] (saved-routine demotion, blank-name or
 * exercise-less promotion), [CUSTOM_EXERCISE_REFERENCED] (delete blocked by a
 * retained workout/routine reference), [NO_COMPLETED_SETS] (finish without a
 * valid completed set), [INVALID_FINISH_TIME] (finish time before the workout
 * start), [SOURCE_NOT_FINISHED] (clone/copy from an unfinished source),
 * [CHILD_BINDING_MISMATCH] (clone/copy plan does not match the current
 * source children in exact order), [HISTORY_SHAPE_CHANGED] (add-exercises
 * plan supplies a different set-ID count than the current derived history
 * shape), and [UNSUPPORTED_MUTATION_KIND].
 */
class FakeSyncService(
    val clockMillis: () -> Long = System::currentTimeMillis,
    val uuidProvider: () -> String = { UUID.randomUUID().toString().lowercase() },
) {
    /** Explicitly seeded global catalogue reference: id to display name. */
    data class CatalogueEntry(val id: String, val name: String)

    /** Global root identity: aggregate type plus ID, owned by one account. */
    data class RootKey(val aggregateType: AggregateType, val aggregateId: String)

    /** Permanent per-mutation rejection codes for the seventeen implemented commands. */
    companion object {
        const val OWNED_ROOT_MISSING = "owned_root_missing"
        const val ROOT_ALREADY_EXISTS = "root_already_exists"
        const val IMMUTABLE_FINISHED = "immutable_finished"
        const val INVALID_REFERENCE = "invalid_reference"
        const val CHILD_COLLISION = "child_collision"
        const val CHILD_BINDING_MISMATCH = "child_binding_mismatch"
        const val HISTORY_SHAPE_CHANGED = "history_shape_changed"
        const val SOURCE_NOT_FINISHED = "source_not_finished"
        const val INVALID_ROUTINE_STATE = "invalid_routine_state"
        const val CUSTOM_EXERCISE_REFERENCED = "custom_exercise_referenced"
        const val NO_COMPLETED_SETS = "no_completed_sets"
        const val INVALID_FINISH_TIME = "invalid_finish_time"
        const val UNSUPPORTED_MUTATION_KIND = "unsupported_mutation_kind"
    }

    /** Internal domain rejection: becomes a REJECTED outcome, never a throw. */
    private class FakeRejection(val errorCode: String) : Exception(errorCode)

    /** Stored root state: live record or retained tombstone with revision. */
    private data class StoredRoot(
        val owner: String,
        val record: CanonicalRecord?,
        val revision: Long,
        val deleted: Boolean,
    )

    /** Fully staged mutation effects, published atomically after validation. */
    private data class StagedEffects(
        val changes: List<SyncChangeModel>,
        val putRoots: Map<RootKey, StoredRoot>,
        val removeExerciseIds: Set<String>,
        val putExerciseOwners: Map<String, RootKey>,
        val removeSetIds: Set<String>,
        val putSetParents: Map<String, String>,
        val activeDelta: ActiveDelta,
    )

    private sealed interface ActiveDelta {
        data object Keep : ActiveDelta
        data class Set(val workoutId: String) : ActiveDelta
        data object Clear : ActiveDelta
    }

    /** Seeded bundled exercise catalogue: id to display name. */
    private val bundledExercises = LinkedHashMap<String, String>()

    /** Seeded muscle-group catalogue: id to display name. */
    private val muscleGroups = LinkedHashMap<String, String>()

    /** Seeded equipment catalogue: id to display name. */
    private val equipment = LinkedHashMap<String, String>()

    private val rootOwners = HashMap<RootKey, String>()
    private val roots = HashMap<RootKey, StoredRoot>()
    private val exerciseOwners = HashMap<String, RootKey>()
    private val setParents = HashMap<String, String>()
    private val activeWorkouts = HashMap<String, String>()
    private val changeLogs = HashMap<String, MutableList<SyncBatchModel>>()
    private val revisionCounters = HashMap<String, Long>()
    private val recordedOutcomes = HashMap<String, MutableMap<String, MutationOutcomeModel>>()

    /**
     * Seeds known global bundled-exercise references explicitly. Legacy alias
     * for [seedBundledExercises]; unknown IDs are never auto-accepted.
     */
    fun seedCatalogue(entries: List<CatalogueEntry>) = seedBundledExercises(entries)

    /** Seeds known global bundled-exercise references explicitly. */
    fun seedBundledExercises(entries: List<CatalogueEntry>) {
        for (entry in entries) {
            bundledExercises[entry.id] = entry.name
        }
    }

    /** Seeds known global muscle-group references explicitly. */
    fun seedMuscleGroups(entries: List<CatalogueEntry>) {
        for (entry in entries) {
            muscleGroups[entry.id] = entry.name
        }
    }

    /** Seeds known global equipment references explicitly. */
    fun seedEquipment(entries: List<CatalogueEntry>) {
        for (entry in entries) {
            equipment[entry.id] = entry.name
        }
    }

    /** Returns true only for explicitly seeded bundled exercises. */
    fun isKnownReference(id: String): Boolean = bundledExercises.containsKey(id)

    /**
     * Registers global ownership of a root by an account. Re-registering to
     * the same account is idempotent; registering to a different account is
     * rejected so owners can never be silently overwritten.
     */
    fun registerRoot(aggregateType: AggregateType, rootId: String, accountId: String) {
        val key = RootKey(aggregateType, rootId)
        val existing = rootOwners[key]
        if (existing != null && existing != accountId) {
            throw SyncModelException("root is already owned by another account")
        }
        rootOwners[key] = accountId
    }

    /** Returns the owning account UUID, or null for an unregistered root. */
    fun ownerOf(aggregateType: AggregateType, rootId: String): String? =
        rootOwners[RootKey(aggregateType, rootId)]

    /** Returns and increments the account revision counter, starting at 1. */
    fun nextRevision(accountId: String): Long {
        val next = (revisionCounters[accountId] ?: 0L) + 1L
        revisionCounters[accountId] = next
        return next
    }

    /**
     * Appends a validated change batch to the account log with generated
     * batch ID and cursor. Batch content is seeded explicitly by tests;
     * command execution commits through the same log. Returns a defensive
     * copy so callers cannot corrupt the stored log through it.
     */
    fun appendChangeBatch(accountId: String, changes: List<SyncChangeModel>): SyncBatchModel {
        val batch = validateSyncBatch(
            SyncBatchModel(
                batchId = uuidProvider(),
                cursor = "cursor-${uuidProvider()}",
                changes = changes,
            ),
        )
        changeLogs.getOrPut(accountId) { mutableListOf() } += batch
        return batch.deepCopy()
    }

    /**
     * Returns deep copies of the seeded account log in append order, for test
     * inspection only. Callers cannot mutate stored canonical state through
     * the returned batches.
     */
    fun loggedBatches(accountId: String): List<SyncBatchModel> =
        changeLogs[accountId].orEmpty().map { it.deepCopy() }

    /**
     * Records a per-mutation outcome for later dedup-replay tasks. Stored and
     * replayed by defensive copy so callers cannot corrupt the original
     * effective result through retained references.
     */
    fun recordOutcome(accountId: String, outcome: MutationOutcomeModel) {
        recordedOutcomes.getOrPut(accountId) { HashMap() }[outcome.mutationId] = outcome.deepCopy()
    }

    /** Replays a defensive copy of a recorded per-mutation outcome, or null when unseen. */
    fun replayOutcome(accountId: String, mutationId: String): MutationOutcomeModel? =
        recordedOutcomes[accountId]?.get(mutationId)?.deepCopy()

    /**
     * Executes validated durable push requests in order for the bound
     * account. Each mutation commits atomically; a rejected mutation changes
     * no state and later mutations still execute. Domain and lookup failures
     * become REJECTED outcomes. Programming errors (malformed wrappers,
     * duplicate mutation IDs, oversized batches) are rejected by
     * the adapter's envelope validation before this runs and throw.
     */
    fun executePush(accountId: String, requests: List<DurableRequestModel>): PushResultModel {
        val outcomes = requests.map { request ->
            val envelope = request.envelope
            try {
                val staged = stageMutation(accountId, envelope)
                val batch = commitBatch(accountId, staged)
                val outcome = MutationOutcomeModel(
                    mutationId = envelope.mutationId,
                    status = MutationStatus.ACCEPTED,
                    errorCode = null,
                    batch = batch.deepCopy(),
                )
                recordOutcome(accountId, outcome)
                outcome
            } catch (rejection: FakeRejection) {
                val outcome = MutationOutcomeModel(
                    mutationId = envelope.mutationId,
                    status = MutationStatus.REJECTED,
                    errorCode = rejection.errorCode,
                    batch = null,
                )
                recordOutcome(accountId, outcome)
                outcome
            }
        }
        return validatePushResult(PushResultModel(outcomes))
    }

    /**
     * Returns a deep copy of the caller's live canonical record, or null for
     * missing, foreign, or tombstoned roots. Never exposes another owner's
     * data.
     */
    fun inspectRecord(accountId: String, aggregateType: AggregateType, aggregateId: String): CanonicalRecord? {
        val key = RootKey(aggregateType, aggregateId)
        if (rootOwners[key] != accountId) return null
        val stored = roots[key] ?: return null
        if (stored.deleted) return null
        return stored.record?.deepCopy()
    }

    /**
     * Returns the last committed revision of a caller-owned root, including
     * tombstones, or null for missing or foreign roots.
     */
    fun inspectRevision(accountId: String, aggregateType: AggregateType, aggregateId: String): Long? {
        val key = RootKey(aggregateType, aggregateId)
        if (rootOwners[key] != accountId) return null
        return roots[key]?.revision
    }

    /** Returns true only for a caller-owned retained tombstone. */
    fun isTombstoned(accountId: String, aggregateType: AggregateType, aggregateId: String): Boolean {
        val key = RootKey(aggregateType, aggregateId)
        if (rootOwners[key] != accountId) return false
        return roots[key]?.deleted == true
    }

    /** Returns the caller's current live active workout ID, or null. */
    fun activeWorkoutId(accountId: String): String? = activeWorkouts[accountId]

    private fun stageMutation(accountId: String, envelope: MutationEnvelopeModel): StagedEffects =
        when (envelope.kind) {
            MutationKind.WORKOUT_START ->
                stageWorkoutStart(accountId, envelope.payload as MutationPayload.WorkoutStart)
            MutationKind.WORKOUT_UPSERT ->
                stageWorkoutUpsert(accountId, (envelope.payload as MutationPayload.WorkoutUpsert).input)
            MutationKind.ROUTINE_UPSERT ->
                stageRoutineUpsert(accountId, (envelope.payload as MutationPayload.RoutineUpsert).input)
            MutationKind.ROUTINE_SAVE ->
                stageRoutineSave(
                    accountId,
                    envelope.aggregateId,
                    (envelope.payload as MutationPayload.RoutineSave).name,
                )
            MutationKind.CUSTOM_EXERCISE_UPSERT ->
                stageCustomExerciseUpsert(
                    accountId,
                    (envelope.payload as MutationPayload.CustomExerciseUpsert).input,
                )
            MutationKind.AGGREGATE_IMPORT ->
                stageImport(accountId, (envelope.payload as MutationPayload.AggregateImport).record)
            MutationKind.AGGREGATE_DELETE ->
                stageDelete(accountId, envelope.aggregateType, envelope.aggregateId)
            MutationKind.WORKOUT_FINISH ->
                stageWorkoutFinish(
                    accountId,
                    envelope.aggregateId,
                    (envelope.payload as MutationPayload.WorkoutFinish).finishedAtMillis,
                )
            MutationKind.WORKOUT_PERFORM_AGAIN ->
                stageWorkoutPerformAgain(
                    accountId,
                    envelope.payload as MutationPayload.WorkoutPerformAgain,
                )
            MutationKind.ROUTINE_START_WORKOUT ->
                stageRoutineStartWorkout(
                    accountId,
                    envelope.payload as MutationPayload.RoutineStartWorkout,
                )
            MutationKind.WORKOUT_SAVE_AS_ROUTINE ->
                stageWorkoutSaveAsRoutine(
                    accountId,
                    envelope.payload as MutationPayload.WorkoutSaveAsRoutine,
                )
            MutationKind.WORKOUT_ADD_EXERCISES ->
                stageWorkoutAddExercises(
                    accountId,
                    envelope.aggregateId,
                    envelope.payload as MutationPayload.WorkoutAddExercises,
                )
            MutationKind.WORKOUT_REPLACE_EXERCISE ->
                stageWorkoutReplaceExercise(
                    accountId,
                    envelope.aggregateId,
                    envelope.payload as MutationPayload.WorkoutReplaceExercise,
                )
            MutationKind.WORKOUT_DELETE_EXERCISE ->
                stageWorkoutDeleteExercise(
                    accountId,
                    envelope.aggregateId,
                    envelope.payload as MutationPayload.WorkoutDeleteExercise,
                )
            MutationKind.WORKOUT_DELETE_SET ->
                stageWorkoutDeleteSet(
                    accountId,
                    envelope.aggregateId,
                    envelope.payload as MutationPayload.WorkoutDeleteSet,
                )
            MutationKind.ROUTINE_DELETE_EXERCISE ->
                stageRoutineDeleteExercise(
                    accountId,
                    envelope.aggregateId,
                    envelope.payload as MutationPayload.RoutineDeleteExercise,
                )
            MutationKind.ROUTINE_DELETE_SET ->
                stageRoutineDeleteSet(
                    accountId,
                    envelope.aggregateId,
                    envelope.payload as MutationPayload.RoutineDeleteSet,
                )
        }

    private fun stageWorkoutStart(accountId: String, start: MutationPayload.WorkoutStart): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, start.id)
        if (rootOwners.containsKey(key) || roots.containsKey(key)) {
            throw FakeRejection(ROOT_ALREADY_EXISTS)
        }
        if (exerciseOwners.containsKey(start.id) || setParents.containsKey(start.id)) {
            throw FakeRejection(CHILD_COLLISION)
        }
        val changes = mutableListOf<SyncChangeModel>()
        val putRoots = HashMap<RootKey, StoredRoot>()
        val priorActive = activeWorkouts[accountId]
        if (priorActive != null) {
            val oldKey = RootKey(AggregateType.WORKOUT, priorActive)
            val oldRoot = roots[oldKey]
            if (oldRoot == null || oldRoot.deleted || oldRoot.owner != accountId) {
                throw SyncModelException("fake invariant violated: stale sole-active pointer")
            }
            val oldRevision = oldRoot.revision + 1L
            changes += SyncChangeModel(
                aggregateType = AggregateType.WORKOUT,
                aggregateId = priorActive,
                operation = ChangeOperation.DELETE,
                revision = oldRevision,
                record = null,
            )
            putRoots[oldKey] = oldRoot.copy(record = null, revision = oldRevision, deleted = true)
        }
        val record = WorkoutRecordModel(
            id = start.id,
            name = start.name,
            status = "active",
            startedAtMillis = start.startedAtMillis,
            finishedAtMillis = null,
            revision = 1L,
            exercises = emptyList(),
        )
        changes += SyncChangeModel(
            aggregateType = AggregateType.WORKOUT,
            aggregateId = start.id,
            operation = ChangeOperation.UPSERT,
            revision = 1L,
            record = CanonicalRecord.Workout(record),
        )
        putRoots[key] = StoredRoot(owner = accountId, record = CanonicalRecord.Workout(record), revision = 1L, deleted = false)
        return StagedEffects(
            changes = changes,
            putRoots = putRoots,
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = ActiveDelta.Set(start.id),
        )
    }

    private fun stageWorkoutUpsert(accountId: String, input: WorkoutInputModel): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, input.id)
        val stored = liveOwnedRoot(accountId, key)
        val record = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (record.record.status != "active") throw FakeRejection(IMMUTABLE_FINISHED)
        checkWorkoutChildren(
            key,
            input.id,
            accountId,
            input.exercises.map { Triple(it.id, it.exerciseId, it.sets.map { set -> set.id }) },
        )
        val exercises = input.exercises.map { entry ->
            WorkoutExerciseModel(
                id = entry.id,
                exerciseId = entry.exerciseId,
                position = entry.position,
                notes = entry.notes,
                restDurationSeconds = entry.restDurationSeconds,
                sets = entry.sets.toList(),
                exerciseName = resolveExerciseName(entry.exerciseId, accountId),
            )
        }
        val revision = stored.revision + 1L
        val next = record.record.copy(
            name = input.name,
            startedAtMillis = input.startedAtMillis,
            revision = revision,
            exercises = exercises,
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = input.id,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Workout(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Workout(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence: they can be restored by a later replacement but never
            // silently reparented into another root.
            removeExerciseIds = emptySet(),
            putExerciseOwners = input.exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = input.exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Finishes the caller's live active workout atomically. Only valid
     * completed sets (complete with present repetitions and weight) survive;
     * unfinished sets and newly empty exercises are removed, survivors at both
     * levels are renumbered consecutively from zero with IDs, notes,
     * configured rest, nullable inputs, and recorded rest preserved, then the
     * status and finish time are set. The known revision and client clocks
     * never affect the result; the next per-root revision is assigned.
     */
    private fun stageWorkoutFinish(accountId: String, workoutId: String, finishedAtMillis: Long): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, workoutId)
        val stored = liveOwnedRoot(accountId, key)
        val record = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (record.record.status != "active") throw FakeRejection(IMMUTABLE_FINISHED)
        if (finishedAtMillis < record.record.startedAtMillis) throw FakeRejection(INVALID_FINISH_TIME)
        val survivors = record.record.exercises.mapNotNull { entry ->
            val kept = entry.sets.filter { it.isCompleted && it.repetitions != null && it.weightKg != null }
            if (kept.isEmpty()) null else entry to kept
        }
        if (survivors.isEmpty()) throw FakeRejection(NO_COMPLETED_SETS)
        val exercises = survivors.mapIndexed { exerciseIndex, (entry, kept) ->
            entry.copy(
                position = exerciseIndex,
                sets = kept.mapIndexed { setIndex, set -> set.copy(position = setIndex) },
            )
        }
        val revision = stored.revision + 1L
        val next = validateWorkoutRecordModel(
            record.record.copy(
                status = "finished",
                finishedAtMillis = finishedAtMillis,
                revision = revision,
                exercises = exercises,
            ),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = workoutId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Workout(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Workout(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence: they can be restored by a later replacement but never
            // silently reparented into another root.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = if (activeWorkouts[accountId] == workoutId) ActiveDelta.Clear else ActiveDelta.Keep,
        )
    }

    /**
     * Creates a fresh active copy of the caller's finished workout. Every
     * planned binding must cover the current source children exactly, in
     * order: exercise count, each exercise's source ID, set count, and each
     * set's source ID. Target IDs are the supplied values only. The new
     * workout copies the source name, exercise references, order, notes,
     * configured rest, and set weight/repetitions; every copied set is
     * incomplete with null recorded rest. A prior active workout is tombstoned
     * in the same ordered DELETE-then-UPSERT batch. The source is unchanged.
     * The known revision and client clock never affect the result.
     */
    private fun stageWorkoutPerformAgain(accountId: String, command: MutationPayload.WorkoutPerformAgain): StagedEffects {
        val sourceKey = RootKey(AggregateType.WORKOUT, command.sourceWorkoutId)
        val stored = liveOwnedRoot(accountId, sourceKey)
        val source = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (source.record.status != "finished") throw FakeRejection(SOURCE_NOT_FINISHED)
        requireCloneBindings(
            command.childIds,
            source.record.exercises.map { it.id },
            source.record.exercises.map { entry -> entry.sets.map { it.id } },
        )
        val targetKey = RootKey(AggregateType.WORKOUT, command.id)
        requireFreshRoot(targetKey, command.id)
        checkWorkoutChildren(
            targetKey,
            command.id,
            accountId,
            command.childIds.map { binding ->
                Triple(binding.id, source.record.exercises.first { it.id == binding.sourceExerciseId }.exerciseId, binding.sets.map { it.id })
            },
        )
        val exercises = command.childIds.mapIndexed { exerciseIndex, binding ->
            val sourceEntry = source.record.exercises[exerciseIndex]
            WorkoutExerciseModel(
                id = binding.id,
                exerciseId = sourceEntry.exerciseId,
                position = exerciseIndex,
                notes = sourceEntry.notes,
                restDurationSeconds = sourceEntry.restDurationSeconds,
                sets = binding.sets.mapIndexed { setIndex, setBinding ->
                    val sourceSet = sourceEntry.sets[setIndex]
                    WorkoutSetModel(
                        id = setBinding.id,
                        position = setIndex,
                        repetitions = sourceSet.repetitions,
                        weightKg = sourceSet.weightKg,
                        isCompleted = false,
                        restSeconds = null,
                    )
                },
                exerciseName = resolveExerciseName(sourceEntry.exerciseId, accountId),
            )
        }
        val canonical = validateWorkoutRecordModel(
            WorkoutRecordModel(
                id = command.id,
                name = source.record.name,
                status = "active",
                startedAtMillis = command.startedAtMillis,
                finishedAtMillis = null,
                revision = 1L,
                exercises = exercises,
            ),
        )
        val changes = mutableListOf<SyncChangeModel>()
        val putRoots = HashMap<RootKey, StoredRoot>()
        val priorActive = activeWorkouts[accountId]
        if (priorActive != null) {
            val oldKey = RootKey(AggregateType.WORKOUT, priorActive)
            val oldRoot = roots[oldKey]
            if (oldRoot == null || oldRoot.deleted || oldRoot.owner != accountId) {
                throw SyncModelException("fake invariant violated: stale sole-active pointer")
            }
            val oldRevision = oldRoot.revision + 1L
            changes += SyncChangeModel(
                aggregateType = AggregateType.WORKOUT,
                aggregateId = priorActive,
                operation = ChangeOperation.DELETE,
                revision = oldRevision,
                record = null,
            )
            putRoots[oldKey] = oldRoot.copy(record = null, revision = oldRevision, deleted = true)
        }
        changes += SyncChangeModel(
            aggregateType = AggregateType.WORKOUT,
            aggregateId = command.id,
            operation = ChangeOperation.UPSERT,
            revision = 1L,
            record = CanonicalRecord.Workout(canonical),
        )
        putRoots[targetKey] = StoredRoot(owner = accountId, record = CanonicalRecord.Workout(canonical), revision = 1L, deleted = false)
        return StagedEffects(
            changes = changes,
            putRoots = putRoots,
            // Source child reservations stay untouched; new child IDs are
            // registered to the new root as their own collision evidence.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to targetKey },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Set(command.id),
        )
    }

    /**
     * Starts a fresh active workout from the caller's saved routine. The
     * source must be saved (`isDraft=false`) with a nonblank name and at
     * least one exercise; a saved routine that became blank or empty through
     * autosave is ineligible. Every planned binding must cover the current
     * source children exactly, in order: exercise count, each exercise's
     * source ID, set count, and each set's source ID. Target IDs are the
     * supplied values only. The new workout copies the routine name,
     * exercise references, order, configured rest, and set
     * weight/repetitions with the normal empty workout notes; every new set
     * is incomplete with null recorded rest. A prior active workout is
     * tombstoned in the same ordered DELETE-then-UPSERT batch. The source is
     * unchanged. The known revision and client clock never affect the result.
     */
    private fun stageRoutineStartWorkout(accountId: String, command: MutationPayload.RoutineStartWorkout): StagedEffects {
        val sourceKey = RootKey(AggregateType.ROUTINE, command.sourceRoutineId)
        val stored = liveOwnedRoot(accountId, sourceKey)
        val source = stored.record as? CanonicalRecord.Routine
            ?: throw SyncModelException("fake invariant violated: routine root holds another record type")
        if (source.record.isDraft) throw FakeRejection(INVALID_ROUTINE_STATE)
        if (source.record.name.isBlank()) throw FakeRejection(INVALID_ROUTINE_STATE)
        if (source.record.exercises.isEmpty()) throw FakeRejection(INVALID_ROUTINE_STATE)
        requireCloneBindings(
            command.childIds,
            source.record.exercises.map { it.id },
            source.record.exercises.map { entry -> entry.sets.map { it.id } },
        )
        val targetKey = RootKey(AggregateType.WORKOUT, command.id)
        requireFreshRoot(targetKey, command.id)
        checkWorkoutChildren(
            targetKey,
            command.id,
            accountId,
            command.childIds.map { binding ->
                Triple(binding.id, source.record.exercises.first { it.id == binding.sourceExerciseId }.exerciseId, binding.sets.map { it.id })
            },
        )
        val exercises = command.childIds.mapIndexed { exerciseIndex, binding ->
            val sourceEntry = source.record.exercises[exerciseIndex]
            WorkoutExerciseModel(
                id = binding.id,
                exerciseId = sourceEntry.exerciseId,
                position = exerciseIndex,
                notes = "",
                restDurationSeconds = sourceEntry.restDurationSeconds,
                sets = binding.sets.mapIndexed { setIndex, setBinding ->
                    val sourceSet = sourceEntry.sets[setIndex]
                    WorkoutSetModel(
                        id = setBinding.id,
                        position = setIndex,
                        repetitions = sourceSet.repetitions,
                        weightKg = sourceSet.weightKg,
                        isCompleted = false,
                        restSeconds = null,
                    )
                },
                exerciseName = resolveExerciseName(sourceEntry.exerciseId, accountId),
            )
        }
        val canonical = validateWorkoutRecordModel(
            WorkoutRecordModel(
                id = command.id,
                name = source.record.name,
                status = "active",
                startedAtMillis = command.startedAtMillis,
                finishedAtMillis = null,
                revision = 1L,
                exercises = exercises,
            ),
        )
        val changes = mutableListOf<SyncChangeModel>()
        val putRoots = HashMap<RootKey, StoredRoot>()
        val priorActive = activeWorkouts[accountId]
        if (priorActive != null) {
            val oldKey = RootKey(AggregateType.WORKOUT, priorActive)
            val oldRoot = roots[oldKey]
            if (oldRoot == null || oldRoot.deleted || oldRoot.owner != accountId) {
                throw SyncModelException("fake invariant violated: stale sole-active pointer")
            }
            val oldRevision = oldRoot.revision + 1L
            changes += SyncChangeModel(
                aggregateType = AggregateType.WORKOUT,
                aggregateId = priorActive,
                operation = ChangeOperation.DELETE,
                revision = oldRevision,
                record = null,
            )
            putRoots[oldKey] = oldRoot.copy(record = null, revision = oldRevision, deleted = true)
        }
        changes += SyncChangeModel(
            aggregateType = AggregateType.WORKOUT,
            aggregateId = command.id,
            operation = ChangeOperation.UPSERT,
            revision = 1L,
            record = CanonicalRecord.Workout(canonical),
        )
        putRoots[targetKey] = StoredRoot(owner = accountId, record = CanonicalRecord.Workout(canonical), revision = 1L, deleted = false)
        return StagedEffects(
            changes = changes,
            putRoots = putRoots,
            // Source child reservations stay untouched; new child IDs are
            // registered to the new root as their own collision evidence.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to targetKey },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Set(command.id),
        )
    }

    /**
     * Saves a copy of the caller's finished workout as a new saved routine
     * under the supplied nonblank name. Every planned binding must cover the
     * current source children exactly, in order. The new routine copies
     * exercise references, order, configured rest, and set
     * weight/repetitions with `isDraft=false`; routine sets carry only
     * nullable weight/repetitions. The finished source and the active pointer
     * are unchanged. The known revision and client clock never affect the
     * result.
     */
    private fun stageWorkoutSaveAsRoutine(accountId: String, command: MutationPayload.WorkoutSaveAsRoutine): StagedEffects {
        val sourceKey = RootKey(AggregateType.WORKOUT, command.sourceWorkoutId)
        val stored = liveOwnedRoot(accountId, sourceKey)
        val source = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (source.record.status != "finished") throw FakeRejection(SOURCE_NOT_FINISHED)
        if (command.name.isBlank()) throw FakeRejection(INVALID_ROUTINE_STATE)
        requireCloneBindings(
            command.childIds,
            source.record.exercises.map { it.id },
            source.record.exercises.map { entry -> entry.sets.map { it.id } },
        )
        val targetKey = RootKey(AggregateType.ROUTINE, command.id)
        requireFreshRoot(targetKey, command.id)
        checkRoutineChildren(
            targetKey,
            command.id,
            command.childIds.map { binding -> binding.id to binding.sets.map { it.id } },
        )
        val exercises = command.childIds.mapIndexed { exerciseIndex, binding ->
            val sourceEntry = source.record.exercises[exerciseIndex]
            RoutineExerciseModel(
                id = binding.id,
                exerciseId = sourceEntry.exerciseId,
                position = exerciseIndex,
                restDurationSeconds = sourceEntry.restDurationSeconds,
                sets = binding.sets.mapIndexed { setIndex, setBinding ->
                    val sourceSet = sourceEntry.sets[setIndex]
                    RoutineSetModel(
                        id = setBinding.id,
                        position = setIndex,
                        repetitions = sourceSet.repetitions,
                        weightKg = sourceSet.weightKg,
                    )
                },
                exerciseName = resolveExerciseName(sourceEntry.exerciseId, accountId),
            )
        }
        val canonical = validateRoutineRecordModel(
            RoutineRecordModel(
                id = command.id,
                name = command.name,
                isDraft = false,
                revision = 1L,
                exercises = exercises,
            ),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.ROUTINE,
                    aggregateId = command.id,
                    operation = ChangeOperation.UPSERT,
                    revision = 1L,
                    record = CanonicalRecord.Routine(canonical),
                ),
            ),
            putRoots = mapOf(targetKey to StoredRoot(owner = accountId, record = CanonicalRecord.Routine(canonical), revision = 1L, deleted = false)),
            // Source child reservations stay untouched; new child IDs are
            // registered to the new root as their own collision evidence.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to targetKey },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Appends caller-visible exercises to the caller's live active workout
     * atomically. Each addition selects one occurrence from current
     * caller-owned finished history: highest finished time, then highest
     * workout UUID, then lowest workout-exercise position. Configured rest
     * and completed set inputs copy from that same occurrence in set order;
     * every new set is incomplete with null recorded rest and new entries
     * start with empty notes. An occurrence with no completed sets still
     * supplies its configured rest alongside exactly one empty set; with no
     * occurrence at all the rest defaults to 30 seconds. The supplied set-ID
     * count must match the derived shape exactly. Repeated catalogue
     * references are allowed, but every new entry/set ID must be fresh:
     * reuse of the target root ID, any retained root/child ID, or an ID
     * already owned by the target root collides. All additions validate
     * before one staged commit; a rejection changes nothing.
     */
    private fun stageWorkoutAddExercises(
        accountId: String,
        workoutId: String,
        command: MutationPayload.WorkoutAddExercises,
    ): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, workoutId)
        val stored = liveOwnedRoot(accountId, key)
        val record = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (record.record.status != "active") throw FakeRejection(IMMUTABLE_FINISHED)
        val plannedIds = command.additions.flatMap { listOf(it.id) + it.setIds }
        if (plannedIds.contains(workoutId) || plannedIds.toSet().size != plannedIds.size) {
            throw FakeRejection(CHILD_COLLISION)
        }
        for (id in plannedIds) {
            if (exerciseOwners.containsKey(id) || setParents.containsKey(id) || isKnownRootId(id)) {
                throw FakeRejection(CHILD_COLLISION)
            }
        }
        val newEntries = command.additions.mapIndexed { additionIndex, addition ->
            val exerciseName = resolveExerciseName(addition.exerciseId, accountId)
            val occurrence = selectHistoryOccurrence(accountId, addition.exerciseId)
            val completed = occurrence?.sets.orEmpty()
                .filter { it.isCompleted && it.repetitions != null && it.weightKg != null }
            val copiedInputs: List<Pair<Int?, Double?>> =
                if (completed.isEmpty()) listOf(null to null) else completed.map { it.repetitions to it.weightKg }
            if (addition.setIds.size != copiedInputs.size) throw FakeRejection(HISTORY_SHAPE_CHANGED)
            WorkoutExerciseModel(
                id = addition.id,
                exerciseId = addition.exerciseId,
                position = record.record.exercises.size + additionIndex,
                notes = "",
                restDurationSeconds = occurrence?.restDurationSeconds ?: 30,
                sets = addition.setIds.mapIndexed { setIndex, setId ->
                    val (repetitions, weightKg) = copiedInputs[setIndex]
                    WorkoutSetModel(
                        id = setId,
                        position = setIndex,
                        repetitions = repetitions,
                        weightKg = weightKg,
                        isCompleted = false,
                        restSeconds = null,
                    )
                },
                exerciseName = exerciseName,
            )
        }
        checkWorkoutChildren(
            key,
            workoutId,
            accountId,
            newEntries.map { Triple(it.id, it.exerciseId, it.sets.map { set -> set.id }) },
        )
        val revision = stored.revision + 1L
        val next = validateWorkoutRecordModel(
            record.record.copy(
                revision = revision,
                exercises = record.record.exercises + newEntries,
            ),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = workoutId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Workout(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Workout(next), revision = revision)),
            // Retained child reservations stay untouched; new child IDs are
            // registered to this root as their own collision evidence.
            removeExerciseIds = emptySet(),
            putExerciseOwners = newEntries.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = newEntries.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Replaces one workout-exercise reference on the caller's live active
     * workout atomically. The target entry must belong to the current child
     * structure of the envelope root; unknown, foreign, wrong-parent, and
     * already-removed IDs reject without disclosing which case applied.
     * Only the catalogue reference and its canonical display name change:
     * the workout-exercise ID, position, notes, configured rest, and every
     * set with its ID/inputs/completion/recorded rest are preserved. The new
     * reference must be caller-visible (seeded bundled or live owned
     * custom). Finished workouts are immutable.
     */
    private fun stageWorkoutReplaceExercise(
        accountId: String,
        workoutId: String,
        command: MutationPayload.WorkoutReplaceExercise,
    ): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, workoutId)
        val stored = liveOwnedRoot(accountId, key)
        val record = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (record.record.status != "active") throw FakeRejection(IMMUTABLE_FINISHED)
        val index = record.record.exercises.indexOfFirst { it.id == command.workoutExerciseId }
        if (index < 0) throw FakeRejection(OWNED_ROOT_MISSING)
        val exerciseName = resolveExerciseName(command.exerciseId, accountId)
        val exercises = record.record.exercises.toMutableList()
        exercises[index] = exercises[index].copy(exerciseId = command.exerciseId, exerciseName = exerciseName)
        val revision = stored.revision + 1L
        val next = validateWorkoutRecordModel(
            record.record.copy(revision = revision, exercises = exercises),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = workoutId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Workout(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Workout(next), revision = revision)),
            // No child identity changes, so no ownership updates are staged.
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Deletes one workout exercise with its sets from the caller's live
     * active workout atomically. The target must belong to the current
     * child structure of the envelope root; unknown, foreign, wrong-parent,
     * and already-removed IDs reject without disclosing which case applied.
     * Surviving exercises are renumbered consecutively from zero in current
     * order. Removed child IDs stay registered as collision evidence.
     * Finished workouts are immutable.
     */
    private fun stageWorkoutDeleteExercise(
        accountId: String,
        workoutId: String,
        command: MutationPayload.WorkoutDeleteExercise,
    ): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, workoutId)
        val stored = liveOwnedRoot(accountId, key)
        val record = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (record.record.status != "active") throw FakeRejection(IMMUTABLE_FINISHED)
        if (record.record.exercises.none { it.id == command.workoutExerciseId }) {
            throw FakeRejection(OWNED_ROOT_MISSING)
        }
        val exercises = record.record.exercises
            .filter { it.id != command.workoutExerciseId }
            .mapIndexed { exerciseIndex, entry -> entry.copy(position = exerciseIndex) }
        val revision = stored.revision + 1L
        val next = validateWorkoutRecordModel(
            record.record.copy(revision = revision, exercises = exercises),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = workoutId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Workout(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Workout(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence: they can be restored by a later replacement but never
            // silently reparented into another root.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Deletes one workout set from the caller's live active workout
     * atomically. The target set must belong to the current child structure
     * of the envelope root; unknown, foreign, wrong-parent, and
     * already-removed IDs reject without disclosing which case applied. The
     * parent exercise is retained even when its last set is removed, and its
     * surviving sets are renumbered consecutively from zero. Removed set IDs
     * stay registered as collision evidence. Finished workouts are
     * immutable.
     */
    private fun stageWorkoutDeleteSet(
        accountId: String,
        workoutId: String,
        command: MutationPayload.WorkoutDeleteSet,
    ): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, workoutId)
        val stored = liveOwnedRoot(accountId, key)
        val record = stored.record as? CanonicalRecord.Workout
            ?: throw SyncModelException("fake invariant violated: workout root holds another record type")
        if (record.record.status != "active") throw FakeRejection(IMMUTABLE_FINISHED)
        val parentIndex = record.record.exercises.indexOfFirst { entry ->
            entry.sets.any { it.id == command.workoutSetId }
        }
        if (parentIndex < 0) throw FakeRejection(OWNED_ROOT_MISSING)
        val exercises = record.record.exercises.mapIndexed { exerciseIndex, entry ->
            if (exerciseIndex != parentIndex) {
                entry
            } else {
                entry.copy(
                    sets = entry.sets
                        .filter { it.id != command.workoutSetId }
                        .mapIndexed { setIndex, set -> set.copy(position = setIndex) },
                )
            }
        }
        val revision = stored.revision + 1L
        val next = validateWorkoutRecordModel(
            record.record.copy(revision = revision, exercises = exercises),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = workoutId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Workout(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Workout(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence: they can be restored by a later replacement but never
            // silently reparented into another root.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Selects the current caller-owned finished occurrence of an exercise
     * reference: highest finished time, then highest workout UUID as the
     * stable tie breaker, then the first workout-exercise position in that
     * workout. Returns null when no finished history references it.
     */
    private fun selectHistoryOccurrence(accountId: String, exerciseId: String): WorkoutExerciseModel? {
        data class Candidate(val finishedAtMillis: Long, val workoutId: String, val entry: WorkoutExerciseModel)
        val candidates = mutableListOf<Candidate>()
        for ((rootKey, stored) in roots) {
            if (rootKey.aggregateType != AggregateType.WORKOUT) continue
            if (stored.deleted || stored.owner != accountId) continue
            val workout = stored.record as? CanonicalRecord.Workout ?: continue
            if (workout.record.status != "finished") continue
            val finishedAt = workout.record.finishedAtMillis ?: continue
            for (entry in workout.record.exercises) {
                if (entry.exerciseId == exerciseId) candidates += Candidate(finishedAt, workout.record.id, entry)
            }
        }
        return candidates.sortedWith(
            compareByDescending<Candidate> { it.finishedAtMillis }
                .thenByDescending { it.workoutId }
                .thenBy { it.entry.position },
        ).firstOrNull()?.entry
    }

    /**
     * Requires the planned clone/copy bindings to cover the current source
     * children completely, in exact order: same exercise count with matching
     * source IDs at each position, and same set count with matching source
     * set IDs at each nested position.
     */
    private fun requireCloneBindings(
        childIds: List<ChildExerciseBinding>,
        sourceExerciseIds: List<String>,
        sourceSetIds: List<List<String>>,
    ) {
        if (childIds.size != sourceExerciseIds.size) throw FakeRejection(CHILD_BINDING_MISMATCH)
        childIds.forEachIndexed { exerciseIndex, binding ->
            if (binding.sourceExerciseId != sourceExerciseIds[exerciseIndex]) {
                throw FakeRejection(CHILD_BINDING_MISMATCH)
            }
            val expectedSets = sourceSetIds[exerciseIndex]
            if (binding.sets.size != expectedSets.size) throw FakeRejection(CHILD_BINDING_MISMATCH)
            binding.sets.forEachIndexed { setIndex, setBinding ->
                if (setBinding.sourceSetId != expectedSets[setIndex]) {
                    throw FakeRejection(CHILD_BINDING_MISMATCH)
                }
            }
        }
    }

    /** Requires a create-only target root with no retained root or child collision. */
    private fun requireFreshRoot(key: RootKey, rootId: String) {
        if (rootOwners.containsKey(key) || roots.containsKey(key)) throw FakeRejection(ROOT_ALREADY_EXISTS)
        if (exerciseOwners.containsKey(rootId) || setParents.containsKey(rootId)) {
            throw FakeRejection(CHILD_COLLISION)
        }
    }

    private fun stageRoutineUpsert(accountId: String, input: RoutineInputModel): StagedEffects {
        val key = RootKey(AggregateType.ROUTINE, input.id)
        val known = rootOwners.containsKey(key) || roots.containsKey(key)
        if (!known) {
            // Ordinary creation is draft-only; promotion uses routine.save.
            if (!input.isDraft) throw FakeRejection(INVALID_ROUTINE_STATE)
            if (exerciseOwners.containsKey(input.id) || setParents.containsKey(input.id)) {
                throw FakeRejection(CHILD_COLLISION)
            }
            checkRoutineChildren(key, input.id, input.exercises.map { it.id to it.sets.map { set -> set.id } })
            val exercises = input.exercises.map { entry ->
                RoutineExerciseModel(
                    id = entry.id,
                    exerciseId = entry.exerciseId,
                    position = entry.position,
                    restDurationSeconds = entry.restDurationSeconds,
                    sets = entry.sets.toList(),
                    exerciseName = resolveExerciseName(entry.exerciseId, accountId),
                )
            }
            val record = RoutineRecordModel(id = input.id, name = input.name, isDraft = true, revision = 1L, exercises = exercises)
            return StagedEffects(
                changes = listOf(
                    SyncChangeModel(
                        aggregateType = AggregateType.ROUTINE,
                        aggregateId = input.id,
                        operation = ChangeOperation.UPSERT,
                        revision = 1L,
                        record = CanonicalRecord.Routine(record),
                    ),
                ),
                putRoots = mapOf(key to StoredRoot(accountId, CanonicalRecord.Routine(record), 1L, false)),
                removeExerciseIds = emptySet(),
                putExerciseOwners = input.exercises.associate { it.id to key },
                removeSetIds = emptySet(),
                putSetParents = input.exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
                activeDelta = ActiveDelta.Keep,
            )
        }
        val stored = liveOwnedRoot(accountId, key)
        val existing = stored.record as? CanonicalRecord.Routine
            ?: throw SyncModelException("fake invariant violated: routine root holds another record type")
        if (existing.record.isDraft != input.isDraft) throw FakeRejection(INVALID_ROUTINE_STATE)
        checkRoutineChildren(key, input.id, input.exercises.map { it.id to it.sets.map { set -> set.id } })
        val exercises = input.exercises.map { entry ->
            RoutineExerciseModel(
                id = entry.id,
                exerciseId = entry.exerciseId,
                position = entry.position,
                restDurationSeconds = entry.restDurationSeconds,
                sets = entry.sets.toList(),
                exerciseName = resolveExerciseName(entry.exerciseId, accountId),
            )
        }
        val revision = stored.revision + 1L
        val next = existing.record.copy(name = input.name, exercises = exercises, revision = revision)
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.ROUTINE,
                    aggregateId = input.id,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Routine(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Routine(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence, exactly like workout replacement.
            removeExerciseIds = emptySet(),
            putExerciseOwners = input.exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = input.exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    private fun stageRoutineSave(accountId: String, routineId: String, name: String): StagedEffects {
        val key = RootKey(AggregateType.ROUTINE, routineId)
        val stored = liveOwnedRoot(accountId, key)
        val existing = stored.record as? CanonicalRecord.Routine
            ?: throw SyncModelException("fake invariant violated: routine root holds another record type")
        if (name.isBlank()) throw FakeRejection(INVALID_ROUTINE_STATE)
        if (existing.record.exercises.isEmpty()) throw FakeRejection(INVALID_ROUTINE_STATE)
        val revision = stored.revision + 1L
        // Promotion re-resolves canonical display names from the current
        // catalogue and caller-owned customs; a referenced custom may have
        // been renamed since the last autosave.
        val exercises = existing.record.exercises.map { entry ->
            entry.copy(exerciseName = resolveExerciseName(entry.exerciseId, accountId))
        }
        val next = existing.record.copy(name = name, isDraft = false, revision = revision, exercises = exercises)
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.ROUTINE,
                    aggregateId = routineId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Routine(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Routine(next), revision = revision)),
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Deletes one routine exercise with its sets from the caller's live
     * routine atomically. Draft and saved routines are both eligible; a
     * saved routine stays saved even when its last exercise is removed. The
     * target must belong to the current child structure of the envelope
     * root; unknown, foreign, wrong-parent, and already-removed IDs reject
     * without disclosing which case applied. Surviving exercises are
     * renumbered consecutively from zero in current order. Removed child IDs
     * stay registered as collision evidence.
     */
    private fun stageRoutineDeleteExercise(
        accountId: String,
        routineId: String,
        command: MutationPayload.RoutineDeleteExercise,
    ): StagedEffects {
        val key = RootKey(AggregateType.ROUTINE, routineId)
        val stored = liveOwnedRoot(accountId, key)
        val existing = stored.record as? CanonicalRecord.Routine
            ?: throw SyncModelException("fake invariant violated: routine root holds another record type")
        if (existing.record.exercises.none { it.id == command.routineExerciseId }) {
            throw FakeRejection(OWNED_ROOT_MISSING)
        }
        val exercises = existing.record.exercises
            .filter { it.id != command.routineExerciseId }
            .mapIndexed { exerciseIndex, entry -> entry.copy(position = exerciseIndex) }
        val revision = stored.revision + 1L
        val next = validateRoutineRecordModel(
            existing.record.copy(revision = revision, exercises = exercises),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.ROUTINE,
                    aggregateId = routineId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Routine(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Routine(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence, exactly like workout replacement.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /**
     * Deletes one routine set from the caller's live routine atomically.
     * Draft and saved routines are both eligible; a saved routine stays
     * saved even when emptied. The target set must belong to the current
     * child structure of the envelope root; unknown, foreign, wrong-parent,
     * and already-removed IDs reject without disclosing which case applied.
     * The parent exercise is retained even when its last set is removed,
     * and its surviving sets are renumbered consecutively from zero.
     * Removed set IDs stay registered as collision evidence.
     */
    private fun stageRoutineDeleteSet(
        accountId: String,
        routineId: String,
        command: MutationPayload.RoutineDeleteSet,
    ): StagedEffects {
        val key = RootKey(AggregateType.ROUTINE, routineId)
        val stored = liveOwnedRoot(accountId, key)
        val existing = stored.record as? CanonicalRecord.Routine
            ?: throw SyncModelException("fake invariant violated: routine root holds another record type")
        val parentIndex = existing.record.exercises.indexOfFirst { entry ->
            entry.sets.any { it.id == command.routineSetId }
        }
        if (parentIndex < 0) throw FakeRejection(OWNED_ROOT_MISSING)
        val exercises = existing.record.exercises.mapIndexed { exerciseIndex, entry ->
            if (exerciseIndex != parentIndex) {
                entry
            } else {
                entry.copy(
                    sets = entry.sets
                        .filter { it.id != command.routineSetId }
                        .mapIndexed { setIndex, set -> set.copy(position = setIndex) },
                )
            }
        }
        val revision = stored.revision + 1L
        val next = validateRoutineRecordModel(
            existing.record.copy(revision = revision, exercises = exercises),
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.ROUTINE,
                    aggregateId = routineId,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.Routine(next),
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = CanonicalRecord.Routine(next), revision = revision)),
            // Removed child IDs stay registered to this root as collision
            // evidence, exactly like workout replacement.
            removeExerciseIds = emptySet(),
            putExerciseOwners = exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    private fun stageCustomExerciseUpsert(
        accountId: String,
        input: CustomExerciseInputModel,
    ): StagedEffects {
        if (bundledExercises.containsKey(input.id)) throw FakeRejection(INVALID_REFERENCE)
        val key = RootKey(AggregateType.CUSTOM_EXERCISE, input.id)
        val owner = rootOwners[key]
        val stored = roots[key]
        val live = stored != null && !stored.deleted && stored.record is CanonicalRecord.CustomExercise
        if (owner != null && owner != accountId) throw FakeRejection(INVALID_REFERENCE)
        if (owner == accountId && stored != null && !live) throw FakeRejection(OWNED_ROOT_MISSING)
        if (owner == null && stored != null) throw FakeRejection(OWNED_ROOT_MISSING)
        val revision = (stored?.revision ?: 0L) + 1L
        val record = buildCustomRecord(input, revision, accountId)
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.CUSTOM_EXERCISE,
                    aggregateId = input.id,
                    operation = ChangeOperation.UPSERT,
                    revision = revision,
                    record = CanonicalRecord.CustomExercise(record),
                ),
            ),
            putRoots = mapOf(key to StoredRoot(accountId, CanonicalRecord.CustomExercise(record), revision, false)),
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    private fun stageImport(accountId: String, record: ImportRecord): StagedEffects =
        when (record) {
            is ImportRecord.Workout -> stageWorkoutImport(accountId, record.input)
            is ImportRecord.Routine -> stageRoutineImport(accountId, record.input)
            is ImportRecord.CustomExercise -> stageCustomImport(accountId, record.input)
        }

    private fun stageWorkoutImport(accountId: String, input: WorkoutImportModel): StagedEffects {
        val key = RootKey(AggregateType.WORKOUT, input.id)
        if (rootOwners.containsKey(key) || roots.containsKey(key)) throw FakeRejection(ROOT_ALREADY_EXISTS)
        if (exerciseOwners.containsKey(input.id) || setParents.containsKey(input.id)) {
            throw FakeRejection(CHILD_COLLISION)
        }
        checkWorkoutChildren(
            key,
            input.id,
            accountId,
            input.exercises.map { Triple(it.id, it.exerciseId, it.sets.map { set -> set.id }) },
        )
        val exercises = input.exercises.map { entry ->
            WorkoutExerciseModel(
                id = entry.id,
                exerciseId = entry.exerciseId,
                position = entry.position,
                notes = entry.notes,
                restDurationSeconds = entry.restDurationSeconds,
                sets = entry.sets.toList(),
                exerciseName = resolveExerciseName(entry.exerciseId, accountId),
            )
        }
        val changes = mutableListOf<SyncChangeModel>()
        val putRoots = HashMap<RootKey, StoredRoot>()
        var activeDelta: ActiveDelta = ActiveDelta.Keep
        if (input.status == "active") {
            val priorActive = activeWorkouts[accountId]
            if (priorActive != null) {
                val oldKey = RootKey(AggregateType.WORKOUT, priorActive)
                val oldRoot = roots[oldKey]
                if (oldRoot == null || oldRoot.deleted || oldRoot.owner != accountId) {
                    throw SyncModelException("fake invariant violated: stale sole-active pointer")
                }
                val oldRevision = oldRoot.revision + 1L
                changes += SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = priorActive,
                    operation = ChangeOperation.DELETE,
                    revision = oldRevision,
                    record = null,
                )
                putRoots[oldKey] = oldRoot.copy(record = null, revision = oldRevision, deleted = true)
            }
            activeDelta = ActiveDelta.Set(input.id)
        }
        val canonical = WorkoutRecordModel(
            id = input.id,
            name = input.name,
            status = input.status,
            startedAtMillis = input.startedAtMillis,
            finishedAtMillis = input.finishedAtMillis,
            revision = 1L,
            exercises = exercises,
        )
        changes += SyncChangeModel(
            aggregateType = AggregateType.WORKOUT,
            aggregateId = input.id,
            operation = ChangeOperation.UPSERT,
            revision = 1L,
            record = CanonicalRecord.Workout(canonical),
        )
        putRoots[key] = StoredRoot(accountId, CanonicalRecord.Workout(canonical), 1L, false)
        return StagedEffects(
            changes = changes,
            putRoots = putRoots,
            removeExerciseIds = emptySet(),
            putExerciseOwners = input.exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = input.exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = activeDelta,
        )
    }

    private fun stageRoutineImport(accountId: String, input: RoutineInputModel): StagedEffects {
        val key = RootKey(AggregateType.ROUTINE, input.id)
        if (rootOwners.containsKey(key) || roots.containsKey(key)) throw FakeRejection(ROOT_ALREADY_EXISTS)
        if (exerciseOwners.containsKey(input.id) || setParents.containsKey(input.id)) {
            throw FakeRejection(CHILD_COLLISION)
        }
        checkRoutineChildren(key, input.id, input.exercises.map { it.id to it.sets.map { set -> set.id } })
        val exercises = input.exercises.map { entry ->
            RoutineExerciseModel(
                id = entry.id,
                exerciseId = entry.exerciseId,
                position = entry.position,
                restDurationSeconds = entry.restDurationSeconds,
                sets = entry.sets.toList(),
                exerciseName = resolveExerciseName(entry.exerciseId, accountId),
            )
        }
        // Import preserves the supplied draft/saved snapshot, including valid
        // saved blank/empty states; ordinary routine.upsert creation stays
        // draft-only and promotion still requires routine.save.
        val canonical = RoutineRecordModel(
            id = input.id,
            name = input.name,
            isDraft = input.isDraft,
            revision = 1L,
            exercises = exercises,
        )
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.ROUTINE,
                    aggregateId = input.id,
                    operation = ChangeOperation.UPSERT,
                    revision = 1L,
                    record = CanonicalRecord.Routine(canonical),
                ),
            ),
            putRoots = mapOf(key to StoredRoot(accountId, CanonicalRecord.Routine(canonical), 1L, false)),
            removeExerciseIds = emptySet(),
            putExerciseOwners = input.exercises.associate { it.id to key },
            removeSetIds = emptySet(),
            putSetParents = input.exercises.flatMap { entry -> entry.sets.map { it.id to entry.id } }.toMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    private fun stageCustomImport(accountId: String, input: CustomExerciseInputModel): StagedEffects {
        if (bundledExercises.containsKey(input.id)) throw FakeRejection(INVALID_REFERENCE)
        val key = RootKey(AggregateType.CUSTOM_EXERCISE, input.id)
        if (rootOwners.containsKey(key) || roots.containsKey(key)) throw FakeRejection(ROOT_ALREADY_EXISTS)
        val record = buildCustomRecord(input, 1L, accountId)
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.CUSTOM_EXERCISE,
                    aggregateId = input.id,
                    operation = ChangeOperation.UPSERT,
                    revision = 1L,
                    record = CanonicalRecord.CustomExercise(record),
                ),
            ),
            putRoots = mapOf(key to StoredRoot(accountId, CanonicalRecord.CustomExercise(record), 1L, false)),
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    private fun stageDelete(accountId: String, aggregateType: AggregateType, aggregateId: String): StagedEffects {
        val key = RootKey(aggregateType, aggregateId)
        val owner = rootOwners[key]
        val stored = roots[key]
        if (owner != accountId || stored == null) throw FakeRejection(OWNED_ROOT_MISSING)
        // Re-deleting a known owned tombstone of any type is idempotent in
        // effect and emits a new deletion revision for convergence.
        if (stored.deleted) return deleteTombstoneCommit(key, stored)
        if (aggregateType == AggregateType.CUSTOM_EXERCISE && isCustomReferenced(accountId, aggregateId)) {
            throw FakeRejection(CUSTOM_EXERCISE_REFERENCED)
        }
        val revision = stored.revision + 1L
        // Children registrations are retained as collision evidence; the
        // canonical record itself is removed.
        val tombstone = stored.copy(record = null, revision = revision, deleted = true)
        var activeDelta: ActiveDelta = ActiveDelta.Keep
        if (aggregateType == AggregateType.WORKOUT && activeWorkouts[accountId] == aggregateId) {
            activeDelta = ActiveDelta.Clear
        }
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    operation = ChangeOperation.DELETE,
                    revision = revision,
                    record = null,
                ),
            ),
            putRoots = mapOf(key to tombstone),
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = activeDelta,
        )
    }

    /** Re-deleting a known owned tombstone is idempotent in effect and emits a new deletion revision. */
    private fun deleteTombstoneCommit(key: RootKey, stored: StoredRoot): StagedEffects {
        val revision = stored.revision + 1L
        return StagedEffects(
            changes = listOf(
                SyncChangeModel(
                    aggregateType = key.aggregateType,
                    aggregateId = key.aggregateId,
                    operation = ChangeOperation.DELETE,
                    revision = revision,
                    record = null,
                ),
            ),
            putRoots = mapOf(key to stored.copy(record = null, revision = revision, deleted = true)),
            removeExerciseIds = emptySet(),
            putExerciseOwners = emptyMap(),
            removeSetIds = emptySet(),
            putSetParents = emptyMap(),
            activeDelta = ActiveDelta.Keep,
        )
    }

    /** Validates the complete staged batch, then publishes every effect atomically. */
    private fun commitBatch(accountId: String, staged: StagedEffects): SyncBatchModel {
        val batch = validateSyncBatch(
            SyncBatchModel(
                batchId = uuidProvider(),
                cursor = "cursor-${uuidProvider()}",
                changes = staged.changes,
            ),
        )
        for ((key, root) in staged.putRoots) {
            roots[key] = root
            rootOwners[key] = root.owner
        }
        for (id in staged.removeExerciseIds) exerciseOwners.remove(id)
        exerciseOwners.putAll(staged.putExerciseOwners)
        for (id in staged.removeSetIds) setParents.remove(id)
        setParents.putAll(staged.putSetParents)
        when (val delta = staged.activeDelta) {
            ActiveDelta.Keep -> Unit
            is ActiveDelta.Set -> activeWorkouts[accountId] = delta.workoutId
            ActiveDelta.Clear -> activeWorkouts.remove(accountId)
        }
        changeLogs.getOrPut(accountId) { mutableListOf() } += batch
        return batch
    }

    private fun liveOwnedRoot(accountId: String, key: RootKey): StoredRoot {
        if (rootOwners[key] != accountId) throw FakeRejection(OWNED_ROOT_MISSING)
        val stored = roots[key]
        if (stored == null || stored.deleted || stored.record == null) throw FakeRejection(OWNED_ROOT_MISSING)
        return stored
    }

    private fun resolveExerciseName(exerciseId: String, accountId: String): String {
        bundledExercises[exerciseId]?.let { return it }
        val key = RootKey(AggregateType.CUSTOM_EXERCISE, exerciseId)
        if (rootOwners[key] == accountId) {
            val stored = roots[key]
            val record = stored?.record as? CanonicalRecord.CustomExercise
            if (stored != null && !stored.deleted && record != null) return record.record.name
        }
        throw FakeRejection(INVALID_REFERENCE)
    }

    private fun buildCustomRecord(
        input: CustomExerciseInputModel,
        revision: Long,
        accountId: String,
    ): CustomExerciseRecordModel {
        val muscles = mutableListOf<MuscleGroupRefModel>()
        input.primaryMuscleGroupId?.let { id ->
            muscles += MuscleGroupRefModel(id, muscleGroups[id] ?: throw FakeRejection(INVALID_REFERENCE), "primary")
        }
        for (id in input.secondaryMuscleGroupIds) {
            muscles += MuscleGroupRefModel(id, muscleGroups[id] ?: throw FakeRejection(INVALID_REFERENCE), "secondary")
        }
        val equipmentRefs = input.equipmentId?.let { id ->
            listOf(EquipmentRefModel(id, equipment[id] ?: throw FakeRejection(INVALID_REFERENCE), input.equipmentNumItems))
        } ?: emptyList()
        return validateCustomExerciseRecordModel(
            CustomExerciseRecordModel(
                id = input.id,
                name = input.name,
                type = "reps",
                category = input.category,
                difficultyLevel = input.difficultyLevel,
                forceType = input.forceType,
                mechanics = input.mechanics,
                instructions = input.instructions?.toList(),
                isUserCreated = true,
                revision = revision,
                muscleGroups = muscles,
                equipment = equipmentRefs,
            ),
        )
    }

    private fun checkWorkoutChildren(
        key: RootKey,
        rootId: String,
        accountId: String,
        exercises: List<Triple<String, String, List<String>>>,
    ) {
        val entryIds = exercises.map { it.first }
        val setIds = exercises.flatMap { it.third }
        // A new child or set must never reuse the incoming root UUID.
        if (entryIds.contains(rootId) || setIds.contains(rootId)) throw FakeRejection(CHILD_COLLISION)
        if (entryIds.intersect(setIds.toSet()).isNotEmpty()) throw FakeRejection(CHILD_COLLISION)
        for ((entryId, _, sets) in exercises) {
            val owner = exerciseOwners[entryId]
            if (owner != null && owner != key) throw FakeRejection(CHILD_COLLISION)
            if (owner == null) {
                if (isKnownRootId(entryId) || setParents.containsKey(entryId)) {
                    throw FakeRejection(CHILD_COLLISION)
                }
            }
            for (setId in sets) {
                val parent = setParents[setId]
                if (parent != null && parent != entryId) throw FakeRejection(CHILD_COLLISION)
                if (parent == null) {
                    if (isKnownRootId(setId) || exerciseOwners.containsKey(setId)) {
                        throw FakeRejection(CHILD_COLLISION)
                    }
                }
            }
        }
        // Stable entry IDs are ownership reservations only: visibility
        // resolves the referenced catalogue or owned-custom exercise ID.
        for ((_, exerciseRef, _) in exercises) {
            resolveExerciseName(exerciseRef, accountId)
        }
    }

    private fun checkRoutineChildren(key: RootKey, rootId: String, exercises: List<Pair<String, List<String>>>) {
        val exerciseIds = exercises.map { it.first }
        val setIds = exercises.flatMap { it.second }
        // A new child or set must never reuse the incoming root UUID.
        if (exerciseIds.contains(rootId) || setIds.contains(rootId)) throw FakeRejection(CHILD_COLLISION)
        if (exerciseIds.intersect(setIds.toSet()).isNotEmpty()) throw FakeRejection(CHILD_COLLISION)
        for ((exerciseId, sets) in exercises) {
            val owner = exerciseOwners[exerciseId]
            if (owner != null && owner != key) throw FakeRejection(CHILD_COLLISION)
            if (owner == null) {
                if (isKnownRootId(exerciseId) || setParents.containsKey(exerciseId)) {
                    throw FakeRejection(CHILD_COLLISION)
                }
            }
            for (setId in sets) {
                val parent = setParents[setId]
                if (parent != null && parent != exerciseId) throw FakeRejection(CHILD_COLLISION)
                if (parent == null) {
                    if (isKnownRootId(setId) || exerciseOwners.containsKey(setId)) {
                        throw FakeRejection(CHILD_COLLISION)
                    }
                }
            }
        }
    }

    private fun isKnownRootId(id: String): Boolean =
        AggregateType.entries.any { roots.containsKey(RootKey(it, id)) }

    /** True when any retained (live) caller-owned workout or routine references the custom exercise. */
    private fun isCustomReferenced(accountId: String, customId: String): Boolean {
        for ((key, stored) in roots) {
            if (stored.deleted || stored.owner != accountId) continue
            when (val record = stored.record) {
                is CanonicalRecord.Workout ->
                    if (record.record.exercises.any { it.exerciseId == customId }) return true
                is CanonicalRecord.Routine ->
                    if (record.record.exercises.any { it.exerciseId == customId }) return true
                is CanonicalRecord.CustomExercise -> Unit
                null -> Unit
            }
        }
        return false
    }

    private fun CanonicalRecord.deepCopy(): CanonicalRecord =
        when (this) {
            is CanonicalRecord.Workout ->
                copy(
                    record = record.copy(
                        exercises = record.exercises.map { entry ->
                            entry.copy(sets = entry.sets.toList())
                        },
                    ),
                )
            is CanonicalRecord.Routine ->
                copy(
                    record = record.copy(
                        exercises = record.exercises.map { entry ->
                            entry.copy(sets = entry.sets.toList())
                        },
                    ),
                )
            is CanonicalRecord.CustomExercise ->
                copy(
                    record = record.copy(
                        muscleGroups = record.muscleGroups.toList(),
                        equipment = record.equipment.toList(),
                        instructions = record.instructions?.toList(),
                    ),
                )
        }

    private fun SyncBatchModel.deepCopy(): SyncBatchModel =
        copy(changes = changes.map { it.copy(record = it.record?.deepCopy()) })

    private fun MutationOutcomeModel.deepCopy(): MutationOutcomeModel =
        copy(batch = batch?.deepCopy())
}
