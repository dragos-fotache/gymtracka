package com.dragosfotache.gymtracka.data.sync.model

/**
 * Shape invariants for typed mutation envelopes and import snapshots.
 * Kind/target/payload-ID matching and value ranges live here; source
 * authorization and current-state rules belong to the later service layer.
 */

fun validateWorkoutImportModel(snapshot: WorkoutImportModel): WorkoutImportModel {
    requireWorkoutUuid(snapshot.id, "id")
    if (snapshot.status != "active" && snapshot.status != "finished") {
        throw SyncModelException("Workout import status must be 'active' or 'finished'")
    }
    requireWireMillis(snapshot.startedAtMillis, "startedAt")
    snapshot.finishedAtMillis?.let { requireWireMillis(it, "finishedAt") }
    if (snapshot.status == "active" && snapshot.finishedAtMillis != null) {
        throw SyncModelException("Active workout import must not carry finishedAt")
    }
    if (snapshot.status == "finished" && snapshot.finishedAtMillis == null) {
        throw SyncModelException("Finished workout import requires finishedAt")
    }
    val finished = snapshot.status == "finished"
    val exercises = validateInputExercises(snapshot.exercises, finished)
    if (finished && exercises.none { exercise -> exercise.sets.any { it.isCompleted } }) {
        throw SyncModelException("Finished workout import requires at least one completed set")
    }
    return snapshot.copy(exercises = exercises)
}

private fun requireMatchingTarget(
    envelope: MutationEnvelopeModel,
    expectedType: AggregateType,
    payloadRootId: String,
) {
    if (envelope.aggregateType != expectedType) {
        throw SyncModelException("Mutation kind does not match envelope aggregate type")
    }
    if (payloadRootId != envelope.aggregateId) {
        throw SyncModelException("Mutation payload ID does not match envelope aggregate ID")
    }
}

fun validateMutationPayload(payload: MutationPayload): MutationPayload {
    when (payload) {
        is MutationPayload.WorkoutStart -> {
            requireWorkoutUuid(payload.id, "id")
            requireWireMillis(payload.startedAtMillis, "startedAt")
        }
        is MutationPayload.WorkoutUpsert ->
            return payload.copy(input = validateWorkoutInputModel(payload.input))
        is MutationPayload.WorkoutFinish -> requireWireMillis(payload.finishedAtMillis, "finishedAt")
        is MutationPayload.WorkoutPerformAgain -> {
            requireWorkoutUuid(payload.sourceWorkoutId, "sourceWorkoutId")
            requireWorkoutUuid(payload.id, "id")
            requireWireMillis(payload.startedAtMillis, "startedAt")
            validateChildPlan(payload.childIds, payload.id, "performAgain")
        }
        is MutationPayload.RoutineStartWorkout -> {
            requireWorkoutUuid(payload.sourceRoutineId, "sourceRoutineId")
            requireWorkoutUuid(payload.id, "id")
            requireWireMillis(payload.startedAtMillis, "startedAt")
            validateChildPlan(payload.childIds, payload.id, "startWorkout")
        }
        is MutationPayload.WorkoutSaveAsRoutine -> {
            requireWorkoutUuid(payload.sourceWorkoutId, "sourceWorkoutId")
            requireWorkoutUuid(payload.id, "id")
            validateChildPlan(payload.childIds, payload.id, "saveAsRoutine")
        }
        is MutationPayload.WorkoutAddExercises -> validateAdditions(payload.additions)
        is MutationPayload.WorkoutReplaceExercise -> {
            requireWorkoutUuid(payload.workoutExerciseId, "workoutExerciseId")
            requireWorkoutUuid(payload.exerciseId, "exerciseId")
        }
        is MutationPayload.WorkoutDeleteExercise ->
            requireWorkoutUuid(payload.workoutExerciseId, "workoutExerciseId")
        is MutationPayload.WorkoutDeleteSet ->
            requireWorkoutUuid(payload.workoutSetId, "workoutSetId")
        is MutationPayload.RoutineUpsert ->
            return payload.copy(input = validateRoutineInputModel(payload.input))
        is MutationPayload.RoutineSave -> Unit
        is MutationPayload.RoutineDeleteExercise ->
            requireWorkoutUuid(payload.routineExerciseId, "routineExerciseId")
        is MutationPayload.RoutineDeleteSet ->
            requireWorkoutUuid(payload.routineSetId, "routineSetId")
        is MutationPayload.CustomExerciseUpsert ->
            return payload.copy(input = validateCustomExerciseInputModel(payload.input))
        is MutationPayload.AggregateDelete -> Unit
        is MutationPayload.AggregateImport ->
            return payload.copy(record = validateImportRecord(payload.record))
    }
    return payload
}

fun validateImportRecord(record: ImportRecord): ImportRecord {
    when (record) {
        is ImportRecord.Workout -> return record.copy(input = validateWorkoutImportModel(record.input))
        is ImportRecord.Routine -> return record.copy(input = validateRoutineInputModel(record.input))
        is ImportRecord.CustomExercise ->
            return record.copy(input = validateCustomExerciseInputModel(record.input))
    }
}

/**
 * Validates a clone/copy child-ID plan: lowercase UUIDs, unique sources and targets,
 * no target aliasing a source ID of the same type, and no target reusing the new
 * root ID. Order is preserved, never sorted; empty plans and empty set bindings
 * are valid shapes and source membership stays server-side.
 */
internal fun validateChildPlan(
    childIds: List<ChildExerciseBinding>,
    rootId: String,
    where: String,
): List<ChildExerciseBinding> {
    childIds.forEach {
        requireWorkoutUuid(it.sourceExerciseId, "$where sourceExerciseId")
        requireWorkoutUuid(it.id, "$where id")
        it.sets.forEach { set ->
            requireWorkoutUuid(set.sourceSetId, "$where sourceSetId")
            requireWorkoutUuid(set.id, "$where set id")
        }
    }
    val sourceExercises = childIds.map { it.sourceExerciseId }
    if (sourceExercises.toSet().size != sourceExercises.size) {
        throw SyncModelException("$where has duplicate source exercise IDs")
    }
    val sourceSets = childIds.flatMap { entry -> entry.sets.map { it.sourceSetId } }
    if (sourceSets.toSet().size != sourceSets.size) {
        throw SyncModelException("$where has duplicate source set IDs")
    }
    val targetExercises = childIds.map { it.id }
    val targetSets = childIds.flatMap { entry -> entry.sets.map { it.id } }
    val allTargets = targetExercises + targetSets
    if (allTargets.toSet().size != allTargets.size) {
        throw SyncModelException("$where has duplicate target IDs")
    }
    if (targetExercises.any { it in sourceExercises }) {
        throw SyncModelException("$where target reuses a source exercise ID")
    }
    if (targetSets.any { it in sourceSets }) {
        throw SyncModelException("$where target reuses a source set ID")
    }
    if (allTargets.contains(rootId)) {
        throw SyncModelException("$where target reuses the new root ID")
    }
    return childIds
}

/**
 * Validates planned history additions: ordered, nonempty, unique entry and set IDs
 * with at least one set ID each. Repeated catalogue exercise references are allowed.
 * Client-materialized history values have no representation here.
 */
internal fun validateAdditions(additions: List<ExerciseAddition>): List<ExerciseAddition> {
    if (additions.isEmpty()) throw SyncModelException("Additions must not be empty")
    additions.forEach {
        requireWorkoutUuid(it.id, "additions[].id")
        requireWorkoutUuid(it.exerciseId, "additions[].exerciseId")
        if (it.setIds.isEmpty()) throw SyncModelException("Addition requires at least one set ID")
        it.setIds.forEach { setId -> requireWorkoutUuid(setId, "additions[].setIds[]") }
    }
    val entryIds = additions.map { it.id }
    if (entryIds.toSet().size != entryIds.size) {
        throw SyncModelException("Additions have duplicate exercise IDs")
    }
    val setIds = additions.flatMap { it.setIds }
    if (setIds.toSet().size != setIds.size) {
        throw SyncModelException("Additions have duplicate set IDs")
    }
    // Planned entry and set IDs share one fresh identity space.
    val allTargets = entryIds + setIds
    if (allTargets.toSet().size != allTargets.size) {
        throw SyncModelException("Additions reuse IDs across exercise/set levels")
    }
    return additions
}

fun validateMutationEnvelope(envelope: MutationEnvelopeModel): MutationEnvelopeModel {
    requireWorkoutUuid(envelope.mutationId, "mutationId")
    requireWorkoutUuid(envelope.aggregateId, "aggregateId")
    if (envelope.localGeneration < 1) throw SyncModelException("localGeneration must be positive")
    envelope.knownServerRevision?.let {
        if (it < 1) throw SyncModelException("knownServerRevision must be positive when supplied")
    }
    val payload = envelope.payload
    when (envelope.kind) {
        MutationKind.WORKOUT_START -> {
            val start = payload as? MutationPayload.WorkoutStart
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.WORKOUT, start.id)
        }
        MutationKind.WORKOUT_UPSERT -> {
            val upsert = payload as? MutationPayload.WorkoutUpsert
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.WORKOUT, upsert.input.id)
        }
        MutationKind.WORKOUT_FINISH -> {
            if (payload !is MutationPayload.WorkoutFinish) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.WORKOUT) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.WORKOUT_PERFORM_AGAIN -> {
            val command = payload as? MutationPayload.WorkoutPerformAgain
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.WORKOUT, command.id)
            if (command.id == command.sourceWorkoutId) {
                throw SyncModelException("New root ID must differ from source")
            }
        }
        MutationKind.ROUTINE_START_WORKOUT -> {
            val command = payload as? MutationPayload.RoutineStartWorkout
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.WORKOUT, command.id)
            if (command.id == command.sourceRoutineId) {
                throw SyncModelException("New root ID must differ from source")
            }
        }
        MutationKind.WORKOUT_SAVE_AS_ROUTINE -> {
            val command = payload as? MutationPayload.WorkoutSaveAsRoutine
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.ROUTINE, command.id)
            if (command.id == command.sourceWorkoutId) {
                throw SyncModelException("New root ID must differ from source")
            }
        }
        MutationKind.WORKOUT_ADD_EXERCISES -> {
            val command = payload as? MutationPayload.WorkoutAddExercises
                ?: throw SyncModelException("Mutation kind does not match payload")
            if (envelope.aggregateType != AggregateType.WORKOUT) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
            val planned = command.additions.flatMap { listOf(it.id) + it.setIds }
            if (planned.contains(envelope.aggregateId)) {
                throw SyncModelException("Addition target reuses the envelope root ID")
            }
        }
        MutationKind.WORKOUT_REPLACE_EXERCISE -> {
            if (payload !is MutationPayload.WorkoutReplaceExercise) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.WORKOUT) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.WORKOUT_DELETE_EXERCISE -> {
            if (payload !is MutationPayload.WorkoutDeleteExercise) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.WORKOUT) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.WORKOUT_DELETE_SET -> {
            if (payload !is MutationPayload.WorkoutDeleteSet) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.WORKOUT) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.ROUTINE_UPSERT -> {
            val upsert = payload as? MutationPayload.RoutineUpsert
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.ROUTINE, upsert.input.id)
        }
        MutationKind.ROUTINE_SAVE -> {
            if (payload !is MutationPayload.RoutineSave) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.ROUTINE) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.ROUTINE_DELETE_EXERCISE -> {
            if (payload !is MutationPayload.RoutineDeleteExercise) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.ROUTINE) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.ROUTINE_DELETE_SET -> {
            if (payload !is MutationPayload.RoutineDeleteSet) {
                throw SyncModelException("Mutation kind does not match payload")
            }
            if (envelope.aggregateType != AggregateType.ROUTINE) {
                throw SyncModelException("Mutation kind does not match envelope aggregate type")
            }
        }
        MutationKind.CUSTOM_EXERCISE_UPSERT -> {
            val upsert = payload as? MutationPayload.CustomExerciseUpsert
                ?: throw SyncModelException("Mutation kind does not match payload")
            requireMatchingTarget(envelope, AggregateType.CUSTOM_EXERCISE, upsert.input.id)
        }
        MutationKind.AGGREGATE_DELETE -> {
            if (payload !is MutationPayload.AggregateDelete) {
                throw SyncModelException("Mutation kind does not match payload")
            }
        }
        MutationKind.AGGREGATE_IMPORT -> {
            val import = payload as? MutationPayload.AggregateImport
                ?: throw SyncModelException("Mutation kind does not match payload")
            val recordType = when (import.record) {
                is ImportRecord.Workout -> AggregateType.WORKOUT
                is ImportRecord.Routine -> AggregateType.ROUTINE
                is ImportRecord.CustomExercise -> AggregateType.CUSTOM_EXERCISE
            }
            if (recordType != envelope.aggregateType) {
                throw SyncModelException("Import record type does not match envelope aggregate type")
            }
            if (import.record.aggregateId != envelope.aggregateId) {
                throw SyncModelException("Import record ID does not match envelope aggregate ID")
            }
        }
    }
    return envelope.copy(payload = validateMutationPayload(payload))
}
