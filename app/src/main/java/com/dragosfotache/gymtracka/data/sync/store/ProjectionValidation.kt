package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid

/**
 * Focused whole-graph helpers for R05c publication. Workout and routine
 * namespaces are independent; exercise roots validate within the exercises
 * table only. Cross-namespace UUID equality (workout vs routine vs exercise
 * reference) is allowed. Same-family role/parent aliases still fail closed.
 * All failures use fixed safe messages without stored values.
 */
internal data class LiveProjectionGraph(
    val workouts: List<WorkoutRecordModel>,
    val routines: List<RoutineRecordModel>,
    val customs: List<CustomExerciseRecordModel>,
    val customIds: Set<String>,
)

internal fun liveProjectionGraph(retained: ValidatedRetained): LiveProjectionGraph {
    val workouts = ArrayList<WorkoutRecordModel>()
    val routines = ArrayList<RoutineRecordModel>()
    val customs = ArrayList<CustomExerciseRecordModel>()
    for ((_, record) in retained.upsertsByKey) {
        when (record) {
            is CanonicalRecord.Workout -> workouts.add(record.record)
            is CanonicalRecord.Routine -> routines.add(record.record)
            is CanonicalRecord.CustomExercise -> customs.add(record.record)
        }
    }
    return LiveProjectionGraph(
        workouts = workouts,
        routines = routines,
        customs = customs,
        customIds = customs.map { it.id }.toSet(),
    )
}

internal fun validateCanonicalGraphDependencies(
    live: LiveProjectionGraph,
    exercisesById: Map<String, ExerciseEntity>,
    muscleIds: Set<String>,
    equipmentIds: Set<String>,
) {
    if (live.workouts.count { it.status == "active" } > 1) {
        throw ProjectionGraphException()
    }
    for (workout in live.workouts) {
        for (entry in workout.exercises) {
            if (entry.exerciseId !in live.customIds) {
                val bundled = exercisesById[entry.exerciseId]
                if (bundled == null ||
                    bundled.isUserCreated ||
                    bundled.accountId != null ||
                    bundled.deletedAtMillis != null
                ) {
                    throw ProjectionGraphException()
                }
            }
        }
    }
    for (routine in live.routines) {
        for (entry in routine.exercises) {
            if (entry.exerciseId !in live.customIds) {
                val bundled = exercisesById[entry.exerciseId]
                if (bundled == null ||
                    bundled.isUserCreated ||
                    bundled.accountId != null ||
                    bundled.deletedAtMillis != null
                ) {
                    throw ProjectionGraphException()
                }
            }
        }
    }
    for (custom in live.customs) {
        for (muscle in custom.muscleGroups) {
            if (muscle.id !in muscleIds) {
                throw ProjectionGraphException()
            }
        }
        for (item in custom.equipment) {
            if (item.id !in equipmentIds) {
                throw ProjectionGraphException()
            }
        }
    }
}

internal data class PhysicalDomainSnapshot(
    val workoutsById: Map<String, WorkoutEntity>,
    val workoutEntriesById: Map<String, WorkoutExerciseEntity>,
    val workoutSetsById: Map<String, WorkoutSetEntity>,
    val routinesById: Map<String, RoutineEntity>,
    val routineEntriesById: Map<String, RoutineExerciseEntity>,
    val routineSetsById: Map<String, RoutineSetEntity>,
    val exercisesById: Map<String, ExerciseEntity>,
)

/**
 * Family-scoped physical identity checks against current domain rows, before any
 * removal. Workout checks stay within workout tables, routine checks within
 * routine tables, custom checks within exercises. Independent namespace overlap
 * (workout vs routine vs exercise IDs) is allowed. A target ID claiming an
 * incompatible same-family identity (local-only/foreign/bundled physical row,
 * role change, or same-account wrong-root/wrong-parent reuse) collides.
 */
internal fun validatePhysicalIdentities(
    snapshot: PhysicalDomainSnapshot,
    accountId: String,
    live: LiveProjectionGraph,
) {
    for (record in live.workouts) {
        if (snapshot.workoutEntriesById.containsKey(record.id) ||
            snapshot.workoutSetsById.containsKey(record.id)
        ) {
            throw ProjectionCollisionException()
        }
        val existingRoot = snapshot.workoutsById[record.id]
        if (existingRoot != null && existingRoot.accountId != accountId) {
            throw ProjectionCollisionException()
        }
        for (entry in record.exercises) {
            if (snapshot.workoutsById.containsKey(entry.id) ||
                snapshot.workoutSetsById.containsKey(entry.id)
            ) {
                throw ProjectionCollisionException()
            }
            val existingEntry = snapshot.workoutEntriesById[entry.id]
            if (existingEntry != null) {
                val parent = snapshot.workoutsById[existingEntry.workoutId]
                    ?: throw CanonicalStorageException()
                if (parent.accountId != accountId) {
                    throw ProjectionCollisionException()
                }
                if (existingEntry.workoutId != record.id) {
                    throw ProjectionCollisionException()
                }
            }
            for (set in entry.sets) {
                if (snapshot.workoutsById.containsKey(set.id) ||
                    snapshot.workoutEntriesById.containsKey(set.id)
                ) {
                    throw ProjectionCollisionException()
                }
                val existingSet = snapshot.workoutSetsById[set.id]
                if (existingSet != null) {
                    val oldEntry = snapshot.workoutEntriesById[existingSet.workoutExerciseId]
                        ?: throw CanonicalStorageException()
                    val oldRoot = snapshot.workoutsById[oldEntry.workoutId]
                        ?: throw CanonicalStorageException()
                    if (oldRoot.accountId != accountId) {
                        throw ProjectionCollisionException()
                    }
                    if (existingSet.workoutExerciseId != entry.id) {
                        throw ProjectionCollisionException()
                    }
                    if (oldEntry.workoutId != record.id) {
                        throw ProjectionCollisionException()
                    }
                }
            }
        }
    }
    for (record in live.routines) {
        if (snapshot.routineEntriesById.containsKey(record.id) ||
            snapshot.routineSetsById.containsKey(record.id)
        ) {
            throw ProjectionCollisionException()
        }
        val existingRoot = snapshot.routinesById[record.id]
        if (existingRoot != null && existingRoot.accountId != accountId) {
            throw ProjectionCollisionException()
        }
        for (entry in record.exercises) {
            if (snapshot.routinesById.containsKey(entry.id) ||
                snapshot.routineSetsById.containsKey(entry.id)
            ) {
                throw ProjectionCollisionException()
            }
            val existingEntry = snapshot.routineEntriesById[entry.id]
            if (existingEntry != null) {
                val parent = snapshot.routinesById[existingEntry.routineId]
                    ?: throw CanonicalStorageException()
                if (parent.accountId != accountId) {
                    throw ProjectionCollisionException()
                }
                if (existingEntry.routineId != record.id) {
                    throw ProjectionCollisionException()
                }
            }
            for (set in entry.sets) {
                if (snapshot.routinesById.containsKey(set.id) ||
                    snapshot.routineEntriesById.containsKey(set.id)
                ) {
                    throw ProjectionCollisionException()
                }
                val existingSet = snapshot.routineSetsById[set.id]
                if (existingSet != null) {
                    val oldEntry = snapshot.routineEntriesById[existingSet.routineExerciseId]
                        ?: throw CanonicalStorageException()
                    val oldRoot = snapshot.routinesById[oldEntry.routineId]
                        ?: throw CanonicalStorageException()
                    if (oldRoot.accountId != accountId) {
                        throw ProjectionCollisionException()
                    }
                    if (existingSet.routineExerciseId != entry.id) {
                        throw ProjectionCollisionException()
                    }
                    if (oldEntry.routineId != record.id) {
                        throw ProjectionCollisionException()
                    }
                }
            }
        }
    }
    for (custom in live.customs) {
        val existing = snapshot.exercisesById[custom.id]
        if (existing != null) {
            if (!existing.isUserCreated || existing.accountId != accountId) {
                throw ProjectionCollisionException()
            }
        }
    }
}

/**
 * Surviving (non-selected-account) workout/routine references to an account
 * custom scheduled for removal fail safely before writes. Selected-account
 * children are replaced wholesale and never trigger this boundary.
 */
internal fun validateSurvivingCustomReferences(
    snapshot: PhysicalDomainSnapshot,
    accountId: String,
    liveCustomIds: Set<String>,
) {
    val ownedCustomIds = snapshot.exercisesById.values
        .filter { it.accountId == accountId && it.isUserCreated }
        .map { it.id }
        .toSet()
    if (ownedCustomIds.isEmpty()) {
        return
    }
    val obsolete = ownedCustomIds - liveCustomIds
    if (obsolete.isEmpty()) {
        return
    }
    for (entry in snapshot.workoutEntriesById.values) {
        val parent = snapshot.workoutsById[entry.workoutId]
            ?: throw CanonicalStorageException()
        if (parent.accountId != accountId && entry.exerciseId in obsolete) {
            throw ProjectionGraphException()
        }
    }
    for (entry in snapshot.routineEntriesById.values) {
        val parent = snapshot.routinesById[entry.routineId]
            ?: throw CanonicalStorageException()
        if (parent.accountId != accountId && entry.exerciseId in obsolete) {
            throw ProjectionGraphException()
        }
    }
}

/**
 * Preserves generation reservations for every existing owned domain root
 * (including tombstoned roots): keeps `max(existing domain generation, ledger
 * watermark)` without inventing a server observation. Rejects negative domain
 * generations. Returns preserved generations for live roots to carry on install.
 */
internal suspend fun preserveGenerationReservations(
    database: GymDatabase,
    accountId: String,
    retained: ValidatedRetained,
    snapshot: PhysicalDomainSnapshot,
    live: LiveProjectionGraph,
): Map<Pair<String, String>, Long> {
    val canonicalDao = database.syncCanonicalDao()
    val preserved = mutableMapOf<Pair<String, String>, Long>()
    suspend fun preserve(type: AggregateType, id: String, domainGeneration: Long?) {
        if (domainGeneration != null && domainGeneration < 0) {
            throw CanonicalStorageException()
        }
        val key = type.wireValue to id
        val watermark = retained.byKey[key]?.localGenerationWatermark ?: 0L
        val target = maxOf(domainGeneration ?: watermark, watermark)
        if (target < 0) {
            throw CanonicalStorageException()
        }
        val existing = retained.byKey[key]
        if (existing == null) {
            try {
                requireWorkoutUuid(id, "aggregateId")
            } catch (_: SyncModelException) {
                throw CanonicalStorageException()
            }
            canonicalDao.insertReservation(accountId, type.wireValue, id, target)
        } else if (existing.localGenerationWatermark < target) {
            val updated = canonicalDao.advanceWatermark(accountId, type.wireValue, id, target)
            if (updated != 1) {
                throw CanonicalStorageException()
            }
        }
        preserved[key] = target
    }
    for ((id, row) in snapshot.workoutsById) {
        if (row.accountId == accountId) {
            preserve(AggregateType.WORKOUT, id, row.localGeneration)
        }
    }
    for ((id, row) in snapshot.routinesById) {
        if (row.accountId == accountId) {
            preserve(AggregateType.ROUTINE, id, row.localGeneration)
        }
    }
    for ((id, row) in snapshot.exercisesById) {
        if (row.accountId == accountId && row.isUserCreated) {
            preserve(AggregateType.CUSTOM_EXERCISE, id, row.localGeneration)
        }
    }
    for (record in live.workouts) {
        val key = AggregateType.WORKOUT.wireValue to record.id
        if (preserved[key] == null) {
            val watermark = retained.byKey[key]?.localGenerationWatermark ?: 0L
            preserved[key] = watermark
        }
    }
    for (record in live.routines) {
        val key = AggregateType.ROUTINE.wireValue to record.id
        if (preserved[key] == null) {
            val watermark = retained.byKey[key]?.localGenerationWatermark ?: 0L
            preserved[key] = watermark
        }
    }
    for (record in live.customs) {
        val key = AggregateType.CUSTOM_EXERCISE.wireValue to record.id
        if (preserved[key] == null) {
            val watermark = retained.byKey[key]?.localGenerationWatermark ?: 0L
            preserved[key] = watermark
        }
    }
    return preserved
}
