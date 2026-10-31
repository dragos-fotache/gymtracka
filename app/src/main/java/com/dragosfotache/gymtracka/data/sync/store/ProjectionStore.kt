package com.dragosfotache.gymtracka.data.sync.store

import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.mapping.toRoomAggregate
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.session.PreparedSessionEnginePermit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Exact account/session/cache-epoch request for canonical projection publication.
 * The account runtime supplies the real edit/timer pause-and-drain barrier;
 * publication validates the lease and durable authority before committing.
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
    private val processCheck: (() -> Unit)? = null,
) {
    init {
        if (session.accountId == null) {
            throw IllegalArgumentException("Projection store requires a signed-in account")
        }
        if (session.sessionGeneration <= 0) {
            throw IllegalArgumentException("Projection store requires a positive session generation")
        }
    }

    /**
     * Nonmutating pairing check for coordinated publication: the publisher
     * binds the SAME physical database instance, account and session
     * generation as the caller's admitted run. Reads only immutable
     * constructor fields; performs no Room, barrier or clock work.
     */
    internal fun bindsTo(db: GymDatabase, accountId: String, generation: Long): Boolean =
        database === db && session.accountId == accountId && session.sessionGeneration == generation

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
        processCheck?.invoke()
        val lease = barrier.acquire(request)
        try {
            try {
                val result = database.withTransaction {
                    publishInsideTransaction(accountId, expectedCacheEpoch, GuardedPublicationLease(lease))
                }
                currentCoroutineContext().ensureActive()
                processCheck?.invoke()
                return result
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

    /**
     * ACTIVE-only canonical timer-impact decision. Read-only and
     * transactionally session/epoch/process fenced. Returns true when the
     * visible timer remains valid under the canonical graph; false when remote
     * finish/delete/replacement, removed entry/set or uncompleted set ends it.
     * Protected outbox intent returns true after fences. Invalid/incomplete or
     * stale data throws; no writes are performed and no UI types are referenced.
     */
    suspend fun preservesTimer(
        expectedCacheEpoch: Long,
        workoutId: String,
        workoutExerciseId: String,
        workoutSetId: String,
    ): Boolean {
        if (expectedCacheEpoch < 0) {
            throw IllegalArgumentException("Expected cache epoch must be nonnegative")
        }
        com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid(workoutId, "workoutId")
        com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid(workoutExerciseId, "workoutExerciseId")
        com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid(workoutSetId, "workoutSetId")
        if (database.inTransaction()) {
            throw IllegalStateException("Timer impact check requires no active transaction")
        }
        currentCoroutineContext().ensureActive()
        processCheck?.invoke()
        val accountId = session.accountId ?: throw IllegalArgumentException(
            "Projection store requires a signed-in account",
        )
        val decision = database.withTransaction {
            currentCoroutineContext().ensureActive()
            processCheck?.invoke()
            val oldEpoch = checkCanonicalFences(
                database,
                accountId,
                session.sessionGeneration,
                expectedCacheEpoch,
            )
            currentCoroutineContext().ensureActive()
            processCheck?.invoke()
            val cache = database.syncCanonicalDao().getAccountCache(accountId)
            if (cache == null || !cache.baselineComplete) {
                throw CanonicalStorageException()
            }
            if (database.syncSnapshotDao().getStageForAccount(accountId) != null) {
                throw SnapshotStageActiveException()
            }
            if (database.syncDao().getOutboxForAccount(accountId).isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                processCheck?.invoke()
                checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch)
                currentCoroutineContext().ensureActive()
                processCheck?.invoke()
                return@withTransaction true
            }
            val retained = loadValidatedRetained(database, accountId, oldEpoch)
            checkFamilyBindings(retained, emptyList())
            val live = liveProjectionGraph(retained)
            val projectionDao = database.syncProjectionDao()
            validateCanonicalGraphDependencies(
                live,
                projectionDao.getAllExercises().associateBy { it.id },
                projectionDao.getMuscleGroupIds().toSet(),
                projectionDao.getEquipmentIds().toSet(),
            )
            val keep = run {
                val workout = live.workouts.singleOrNull { it.id == workoutId }
                    ?: return@run false
                if (workout.status != "active") return@run false
                val entry = workout.exercises.singleOrNull { it.id == workoutExerciseId }
                    ?: return@run false
                val set = entry.sets.singleOrNull { it.id == workoutSetId }
                    ?: return@run false
                set.isCompleted
            }
            currentCoroutineContext().ensureActive()
            processCheck?.invoke()
            checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch)
            currentCoroutineContext().ensureActive()
            processCheck?.invoke()
            keep
        }
        currentCoroutineContext().ensureActive()
        processCheck?.invoke()
        database.withTransaction {
            checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch)
        }
        currentCoroutineContext().ensureActive()
        processCheck?.invoke()
        return decision
    }

    /**
     * Explicit internal paired publication entry for inert prepared completion.
     * Must run inside the caller's active Room transaction (the same
     * transaction that carries the journal/device activation, so publication
     * and activation roll back together). Reuses the exact whole-graph
     * validation, collision checks, account-only replacement, generation
     * preservation and materialization as the public path; only the fence
     * carries the registered prepared permit instead of ACTIVE device state.
     * Performs no barrier, vault, HTTP or clock work inside Room. The caller
     * holds the shared owner and handling locks and owns barrier acquisition
     * and lease lifetime outside Room.
     */
    internal suspend fun publishPreparedInsideTransaction(
        accountId: String,
        expectedCacheEpoch: Long,
        lease: ProjectionPublicationLease,
        permit: PreparedSessionEnginePermit,
    ): ProjectionPublicationResult {
        if (!database.inTransaction()) {
            throw IllegalStateException("Prepared publication requires an active transaction")
        }
        return publishGraphInsideTransaction(accountId, expectedCacheEpoch, lease, permit)
    }

    private suspend fun publishInsideTransaction(
        accountId: String,
        expectedCacheEpoch: Long,
        lease: ProjectionPublicationLease,
    ): ProjectionPublicationResult {
        currentCoroutineContext().ensureActive()
        processCheck?.invoke()
        lease.checkValid()
        val oldEpoch = checkCanonicalFences(
            database,
            accountId,
            session.sessionGeneration,
            expectedCacheEpoch,
        )
        return publishGraphFromEpoch(accountId, expectedCacheEpoch, lease, null, oldEpoch)
    }

    /**
     * Shared whole-graph publication body from an already-fenced epoch.
     * A null permit keeps the ordinary ACTIVE device fence; a nonnull permit
     * uses the registered prepared fence instead. No new owner, barrier or
     * permissive lease is introduced here.
     */
    private suspend fun publishGraphInsideTransaction(
        accountId: String,
        expectedCacheEpoch: Long,
        lease: ProjectionPublicationLease,
        permit: PreparedSessionEnginePermit,
    ): ProjectionPublicationResult {
        currentCoroutineContext().ensureActive()
        lease.checkValid()
        val oldEpoch = checkCanonicalFences(
            database,
            accountId,
            session.sessionGeneration,
            expectedCacheEpoch,
            permit,
        )
        return publishGraphFromEpoch(accountId, expectedCacheEpoch, lease, permit, oldEpoch)
    }

    private suspend fun publishGraphFromEpoch(
        accountId: String,
        expectedCacheEpoch: Long,
        lease: ProjectionPublicationLease,
        permit: PreparedSessionEnginePermit?,
        oldEpoch: Long,
    ): ProjectionPublicationResult {
        val canonicalDao = database.syncCanonicalDao()
        val syncDao = database.syncDao()
        val projectionDao = database.syncProjectionDao()
        val cache = canonicalDao.getAccountCache(accountId)
        val currentCacheEpoch = cache?.cacheEpoch ?: oldEpoch
        val currentMaterialized = cache?.materializedEpoch ?: 0L
        if (database.syncSnapshotDao().getStageForAccount(accountId) != null) {
            throw SnapshotStageActiveException()
        }
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
        if (permit == null) processCheck?.invoke()
        lease.checkValid()
        checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch, permit)
        replaceAccountGraph(accountId, live, snapshot, preserved)
        currentCoroutineContext().ensureActive()
        if (permit == null) processCheck?.invoke()
        lease.checkValid()
        checkCanonicalFences(database, accountId, session.sessionGeneration, expectedCacheEpoch, permit)
        val newEpoch = try {
            Math.addExact(oldEpoch, 1L)
        } catch (_: ArithmeticException) {
            throw CanonicalStorageException()
        }
        val updated = canonicalDao.publishProjection(accountId, newEpoch)
        if (updated != 1) {
            throw CanonicalStorageException()
        }
        // Last-precommit guards AFTER all SQL effects: revocation or terminal admission
        // racing the final metadata write must fail closed with rollback instead of
        // committing. Rechecks run at the NEW returned epoch because this publication
        // advanced it; the old epoch is never rechecked after the advance. The ACTIVE
        // path adds the exact process fence; the prepared path keeps final lease
        // validity with its owning combined completion fences (permit, no process check).
        currentCoroutineContext().ensureActive()
        lease.checkValid()
        if (permit == null) {
            processCheck?.invoke()
        }
        checkCanonicalFences(database, accountId, session.sessionGeneration, newEpoch, permit)
        currentCoroutineContext().ensureActive()
        lease.checkValid()
        if (permit == null) {
            processCheck?.invoke()
        }
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
