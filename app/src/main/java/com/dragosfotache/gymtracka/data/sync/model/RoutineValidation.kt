package com.dragosfotache.gymtracka.data.sync.model

/**
 * Shape invariants for routine aggregates. Blank names and empty children are valid:
 * autosaved drafts and in-progress edits must pass through; promotion rules live
 * in the service layer. IDs are never generated or remapped here.
 */

private fun validatedRoutineSets(
    sets: List<RoutineSetModel>,
    where: String,
): List<RoutineSetModel> {
    // routine_sets has one global primary key: set IDs are unique across the aggregate.
    val sorted = sets.sortedBy { it.position }
    sorted.forEachIndexed { index, set ->
        if (set.position != index) throw SyncModelException("$where sets have a position gap or duplicate")
        requireWorkoutUuid(set.id, "$where sets[].id")
        requireNullableInputs(set.repetitions, set.weightKg, "$where set")
    }
    return sorted
}

/** Validates entries, sorts exercises and sets by explicit position, returns sorted list. */
internal fun validateRoutineInputExercises(
    exercises: List<RoutineExerciseInputModel>,
): List<RoutineExerciseInputModel> {
    val ids = exercises.map { it.id }
    if (ids.toSet().size != ids.size) throw SyncModelException("Routine has duplicate exercise IDs")
    val sorted = exercises.sortedBy { it.position }
    checkConsecutivePositions(sorted.map { it.position }, "Routine exercises")
    val allSetIds = sorted.flatMap { entry -> entry.sets.map { it.id } }
    if (allSetIds.toSet().size != allSetIds.size) throw SyncModelException("Routine has duplicate set IDs")
    return sorted.map { entry ->
        requireWorkoutUuid(entry.id, "Routine exercise id")
        requireWorkoutUuid(entry.exerciseId, "Routine exercise exerciseId")
        if (entry.restDurationSeconds < 0) {
            throw SyncModelException("Routine exercise has negative restDurationSeconds")
        }
        entry.copy(sets = validatedRoutineSets(entry.sets, "Routine exercise"))
    }
}

/** Validates entries, sorts exercises and sets by explicit position, returns sorted list. */
internal fun validateRoutineRecordExercises(
    exercises: List<RoutineExerciseModel>,
): List<RoutineExerciseModel> {
    val ids = exercises.map { it.id }
    if (ids.toSet().size != ids.size) throw SyncModelException("Routine has duplicate exercise IDs")
    val sorted = exercises.sortedBy { it.position }
    checkConsecutivePositions(sorted.map { it.position }, "Routine exercises")
    val allSetIds = sorted.flatMap { entry -> entry.sets.map { it.id } }
    if (allSetIds.toSet().size != allSetIds.size) throw SyncModelException("Routine has duplicate set IDs")
    return sorted.map { entry ->
        requireWorkoutUuid(entry.id, "Routine exercise id")
        requireWorkoutUuid(entry.exerciseId, "Routine exercise exerciseId")
        if (entry.restDurationSeconds < 0) {
            throw SyncModelException("Routine exercise has negative restDurationSeconds")
        }
        entry.copy(sets = validatedRoutineSets(entry.sets, "Routine exercise"))
    }
}

fun validateRoutineInputModel(input: RoutineInputModel): RoutineInputModel {
    requireWorkoutUuid(input.id, "id")
    return input.copy(exercises = validateRoutineInputExercises(input.exercises))
}

fun validateRoutineRecordModel(record: RoutineRecordModel): RoutineRecordModel {
    requireWorkoutUuid(record.id, "id")
    requirePositiveRevision(record.revision, "Routine record")
    return record.copy(exercises = validateRoutineRecordExercises(record.exercises))
}
