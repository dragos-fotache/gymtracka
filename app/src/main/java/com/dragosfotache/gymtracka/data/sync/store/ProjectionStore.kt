package com.dragosfotache.gymtracka.data.sync.store

import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.mapping.toRoomAggregate
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Inert account-scoped canonical publication boundary over the accepted R05a/b
 * foundations. Production remains signed-out/local-only; no engine, network,
 * claim/ack, snapshot, vault, UI, timer, scheduler or backend work runs here.
 * The barrier owns pause/drain coordination; this store implements the interface
 * boundary only. Tests supply controllable leases.
 */
data class ProjectionPublicationRequest(
    val accountId: String,
    val sessionGeneration: Long,
    val expectedCacheEpoch: Long,
)

fun interface ProjectionPublicationBarrier {
    suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease
}

interface ProjectionPublicationLease : AutoCloseable {
    fun checkValid()
    override fun close()
}

enum class ProjectionPublicationStatus {
    PUBLISHED,
    PROTECTED,
    INCOMPLETE_BASELINE,
    UNCHANGED,
}

data class ProjectionPublicationResult(
    val status: ProjectionPublicationStatus,
    val cacheEpoch: Long,
    val materializedEpoch: Long,
)

class ProjectionLeaseInvalidException : IllegalStateException("Projection lease is no longer valid")

class ProjectionGraphException : IllegalStateException("Invalid canonical projection graph")

class ProjectionCollisionException : IllegalStateException("Projection collides with existing data")

/**
 * Internal marker preserving barrier/lease callback failure provenance across the
 * Room transaction. Never escapes: [ProjectionStore.publish] unwraps and rethrows
 * the origin so lease-origin failures (including SQLite failures raised by the
 * lease itself) retain their identity instead of being sanitized as
 * projection-owned storage errors. The marker has no message or cause chain;
 * its origin reference is only unwrapped, never logged or rendered.
 */
private class LeaseOriginFailure(val origin: RuntimeException) : RuntimeException()

private class GuardedPublicationLease(
    private val delegate: ProjectionPublicationLease,
) : ProjectionPublicationLease {
    override fun checkValid() {
        try {
            delegate.checkValid()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            throw LeaseOriginFailure(e)
        }
    }

    override fun close() = delegate.close()
}

class ProjectionStore(
    private val database: GymDatabase,
    private val session: RepositorySession,
    private val barrier: ProjectionPublicationBarrier,
) {
    init {
        if (session.accountId == null) {
            throw IllegalArgumentException("Projection store requires a signed-in account")
        }
        if (session.sessionGeneration <= 0) {
            throw IllegalArgumentException("Projection store requires a positive session generation")
        }
    }

    suspend fun publish(expectedCacheEpoch: Long): ProjectionPublicationResult {
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        if (database.inTransaction()) {
            throw IllegalStateException("Projection publication requires no active transaction")
        }
        val accountId = session.accountId ?: throw IllegalArgumentException(
            "Projection store requires a signed-in account",
        )
        val request = ProjectionPublicationRequest(
            accountId = accountId,
            sessionGeneration = session.sessionGeneration,
            expectedCacheEpoch = expectedCacheEpoch,
        )
        currentCoroutineContext().ensureActive()
        val lease = barrier.acquire(request)
        try {
            try {
                return database.withTransaction {
                    publishInsideTransaction(accountId, expectedCacheEpoch, GuardedPublicationLease(lease))
                }
            } catch (e: LeaseOriginFailure) {
                currentCoroutineContext().ensureActive()
                throw e.origin
            } catch (_: android.database.sqlite.SQLiteException) {
                currentCoroutineContext().ensureActive()
                throw CanonicalStorageException()
            }
        } finally {
            lease.close()
        }
    }

    private suspend fun publishInsideTransaction(
        accountId: String,
        expectedCacheEpoch: Long,
        lease: ProjectionPublicationLease,
    ): ProjectionPublicationResult {
        currentCoroutineContext().ensureActive()
        lease.checkValid()
        val oldEpoch = checkCanonicalFences(
            database,
            accountId,
            session.sessionGeneration,
            expectedCacheEpoch,
        )
        val canonicalDao = database.syncCanonicalDao()
        val syncDao = database.syncDao()
        val projectionDao = database.syncProjectionDao()
        val cache = canonicalDao.getAccountCache(accountId)
        val currentCacheEpoch = cache?.cacheEpoch ?: oldEpoch
        val currentMaterialized = cache?.materializedEpoch ?: 0L
        if (syncDao.getOutboxForAccount(accountId).isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            lease.checkValid()
            return ProjectionPublicationResult(
                status = ProjectionPublicationStatus.PROTECTED,
                cacheEpoch = currentCacheEpoch,
                materializedEpoch = currentMaterialized,
            )
        }
        if (cache == null || !cache.baselineComplete) {
            currentCoroutineContext().ensureActive()
            lease.checkValid()
            return ProjectionPublicationResult(
                status = ProjectionPublicationStatus.INCOMPLETE_BASELINE,
                cacheEpoch = currentCacheEpoch,
                materializedEpoch = currentMaterialized,
            )
        }
        if (cache.materializedEpoch == cache.cacheEpoch && !cache.projectionDirty) {
            currentCoroutineContext().ensureActive()
            lease.checkValid()
            return ProjectionPublicationResult(
                status = ProjectionPublicationStatus.UNCHANGED,
                cacheEpoch = cache.cacheEpoch,
                materializedEpoch = cache.materializedEpoch,
            )
        }
        if (oldEpoch == Long.MAX_VALUE) {
            throw CanonicalStorageException()
        }
        val retained = loadValidatedRetained(database, accountId, oldEpoch)
        checkFamilyBindings(retained, emptyList())
        val live = liveProjectionGraph(retained)
        val workouts = projectionDao.getAllWorkouts()
        val workoutEntries = projectionDao.getAllWorkoutExercises()
        val workoutSets = projectionDao.getAllWorkoutSets()
        val routines = projectionDao.getAllRoutines()
        val routineEntries = projectionDao.getAllRoutineExercises()
        val routineSets = projectionDao.getAllRoutineSets()
        val exercises = projectionDao.getAllExercises()
        val snapshot = PhysicalDomainSnapshot(
            workoutsById = workouts.associateBy { it.id },
            workoutEntriesById = workoutEntries.associateBy { it.id },
            workoutSetsById = workoutSets.associateBy { it.id },
            routinesById = routines.associateBy { it.id },
            routineEntriesById = routineEntries.associateBy { it.id },
            routineSetsById = routineSets.associateBy { it.id },
            exercisesById = exercises.associateBy { it.id },
        )
        val muscleIds = projectionDao.getMuscleGroupIds().toSet()
        val equipmentIds = projectionDao.getEquipmentIds().toSet()
        validateCanonicalGraphDependencies(live, snapshot.exercisesById, muscleIds, equipmentIds)
        validatePhysicalIdentities(snapshot, accountId, live)
        validateSurvivingCustomReferences(snapshot, accountId, live.customIds)
        val preserved = preserveGenerationReservations(database, accountId, retained, snapshot, live)
        currentCoroutineContext().ensureActive()
        lease.checkValid()
        checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch)
        replaceAccountGraph(accountId, live, snapshot, preserved)
        currentCoroutineContext().ensureActive()
        lease.checkValid()
        checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch)
        val newEpoch = try {
            Math.addExact(oldEpoch, 1L)
        } catch (_: ArithmeticException) {
            throw CanonicalStorageException()
        }
        val updated = canonicalDao.publishProjection(accountId, newEpoch)
        if (updated != 1) {
            throw CanonicalStorageException()
        }
        currentCoroutineContext().ensureActive()
        return ProjectionPublicationResult(
            status = ProjectionPublicationStatus.PUBLISHED,
            cacheEpoch = newEpoch,
            materializedEpoch = newEpoch,
        )
    }

    private suspend fun replaceAccountGraph(
        accountId: String,
        live: LiveProjectionGraph,
        snapshot: PhysicalDomainSnapshot,
        preserved: Map<Pair<String, String>, Long>,
    ) {
        val projectionDao = database.syncProjectionDao()
        projectionDao.deleteWorkoutExercisesForAccount(accountId)
        projectionDao.deleteWorkoutsForAccount(accountId)
        projectionDao.deleteRoutineExercisesForAccount(accountId)
        projectionDao.deleteRoutinesForAccount(accountId)
        projectionDao.deleteCustomMuscleGroupsForAccount(accountId)
        projectionDao.deleteCustomEquipmentForAccount(accountId)
        val ownedCustomIds = snapshot.exercisesById.values
            .filter { it.accountId == accountId && it.isUserCreated }
            .map { it.id }
            .toSet()
        val obsolete = ownedCustomIds - live.customIds
        for (obsoleteId in obsolete) {
            projectionDao.deleteCustomExerciseById(accountId, obsoleteId)
        }
        try {
            val customInserts = ArrayList<com.dragosfotache.gymtracka.data.gym.ExerciseEntity>()
            val customUpdates = ArrayList<com.dragosfotache.gymtracka.data.gym.ExerciseEntity>()
            val muscleRows = ArrayList<com.dragosfotache.gymtracka.data.gym.ExerciseMuscleGroupEntity>()
            val equipmentRows = ArrayList<com.dragosfotache.gymtracka.data.gym.ExerciseEquipmentEntity>()
            for (record in live.customs) {
                val generation = preserved[AggregateType.CUSTOM_EXERCISE.wireValue to record.id] ?: 0L
                val aggregate = record.toRoomAggregate(accountId, generation)
                if (snapshot.exercisesById.containsKey(record.id)) {
                    customUpdates.add(aggregate.exercise)
                } else {
                    customInserts.add(aggregate.exercise)
                }
                muscleRows.addAll(aggregate.muscleGroups)
                equipmentRows.addAll(aggregate.equipment)
            }
            if (customUpdates.isNotEmpty()) {
                val updatedRows = projectionDao.updateExercises(customUpdates)
                if (updatedRows != customUpdates.size) {
                    throw CanonicalStorageException()
                }
            }
            if (customInserts.isNotEmpty()) {
                projectionDao.insertExercises(customInserts)
            }
            if (muscleRows.isNotEmpty()) {
                projectionDao.insertExerciseMuscleGroups(muscleRows)
            }
            if (equipmentRows.isNotEmpty()) {
                projectionDao.insertExerciseEquipment(equipmentRows)
            }
            val workoutRoots = ArrayList<com.dragosfotache.gymtracka.data.gym.WorkoutEntity>()
            val workoutEntries = ArrayList<com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity>()
            val workoutSets = ArrayList<com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity>()
            for (record in live.workouts) {
                val generation = preserved[AggregateType.WORKOUT.wireValue to record.id] ?: 0L
                val aggregate = record.toRoomAggregate(accountId, generation)
                workoutRoots.add(aggregate.workout)
                workoutEntries.addAll(aggregate.exercises)
                workoutSets.addAll(aggregate.sets)
            }
            val routineRoots = ArrayList<com.dragosfotache.gymtracka.data.gym.RoutineEntity>()
            val routineEntries = ArrayList<com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity>()
            val routineSets = ArrayList<com.dragosfotache.gymtracka.data.gym.RoutineSetEntity>()
            for (record in live.routines) {
                val generation = preserved[AggregateType.ROUTINE.wireValue to record.id] ?: 0L
                val aggregate = record.toRoomAggregate(accountId, generation)
                routineRoots.add(aggregate.routine)
                routineEntries.addAll(aggregate.exercises)
                routineSets.addAll(aggregate.sets)
            }
            if (workoutRoots.isNotEmpty()) {
                projectionDao.insertWorkouts(workoutRoots)
            }
            if (workoutEntries.isNotEmpty()) {
                projectionDao.insertWorkoutExercises(workoutEntries)
            }
            if (workoutSets.isNotEmpty()) {
                projectionDao.insertWorkoutSets(workoutSets)
            }
            if (routineRoots.isNotEmpty()) {
                projectionDao.insertRoutines(routineRoots)
            }
            if (routineEntries.isNotEmpty()) {
                projectionDao.insertRoutineExercises(routineEntries)
            }
            if (routineSets.isNotEmpty()) {
                projectionDao.insertRoutineSets(routineSets)
            }
        } catch (_: SyncModelException) {
            throw ProjectionGraphException()
        }
    }
}
