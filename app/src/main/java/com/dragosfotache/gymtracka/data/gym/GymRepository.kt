package com.dragosfotache.gymtracka.data.gym

import android.content.Context
import com.dragosfotache.gymtracka.data.sync.mapping.toRoutineInput
import com.dragosfotache.gymtracka.data.sync.mapping.toWorkoutInput
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.ChildSetBinding
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import com.dragosfotache.gymtracka.data.sync.store.SyncTransaction
import java.util.UUID

/**
 * Application-facing entry point for catalogue, workout, and routine persistence.
 *
 * Bound to an immutable [session] fence: application reads select only the session
 * account's visible roots (signed-out reads show separate local roots), and custom
 * mutations capture through fenced Room transactions. An old repository never
 * follows a newly active device account; later lifecycle code constructs a new one.
 */
internal class GymRepository(
    private val database: GymDatabase,
    private val session: RepositorySession = RepositorySession.SIGNED_OUT,
    private val newMutationId: () -> String = { UUID.randomUUID().toString() },
    private val tombstoneClock: () -> Long = System::currentTimeMillis,
) : ExerciseStateRepository,
    GymAppStateRepository,
    ActiveWorkoutStateRepository,
    RoutineStateRepository,
    WorkoutHistoryStateRepository {
    /** Production construction path. Tests can inject an isolated [GymDatabase] directly. */
    constructor(context: Context) : this(GymDatabase.getInstance(context))

    private val store = SyncStore(database, session, newMutationId)

    private companion object {
        const val DEFAULT_REST_DURATION_SECONDS = 30

        /**
         * Narrow input rule: nullable set inputs stay nullable, but negative or
         * nonfinite values sanitize to null (matching the upload shape) instead of
         * failing an ordinary draft edit. Configured rest stays nonnegative.
         */
        fun sanitizeRepetitions(repetitions: Int?): Int? =
            repetitions?.takeIf { it >= 0 }

        fun sanitizeWeight(weightKg: Double?): Double? =
            weightKg?.takeIf { it >= 0.0 && it.isFinite() }
    }

    override suspend fun muscleGroups() = store.withFencedTransaction {
        database.muscleGroupDao().getAll()
    }

    override suspend fun equipment() = store.withFencedTransaction {
        database.equipmentDao().getAll()
    }

    suspend fun exercises() = store.withFencedTransaction {
        database.exerciseDao().getScopedAll(session.accountId)
    }

    override suspend fun exerciseCategories() = store.withFencedTransaction {
        database.exerciseDao().getScopedCategories(session.accountId)
    }

    override suspend fun exerciseSummaries() = store.withFencedTransaction {
        database.exerciseDao().getScopedSummaries(session.accountId)
    }

    suspend fun exercise(id: String) = store.withFencedTransaction {
        database.exerciseDao().getScopedById(id, session.accountId)
    }

    override suspend fun exerciseDetails(exerciseId: String) = store.withFencedTransaction {
        database.exerciseDao().getScopedDetails(exerciseId, session.accountId)
    }

    override suspend fun createCustomExercise(
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    ): String = store.withFencedTransaction {
        if (name.isBlank()) throw SyncModelException("Custom exercise name must be nonblank")
        val exerciseId = UUID.randomUUID().toString()
        val normalizedInstructions = instructions.trim().takeIf { it.isNotEmpty() }?.let(::listOf)
        val distinctSecondaries = secondaryMuscleGroupIds
            .filter { it != primaryMuscleGroupId }
            .sorted()
        val exercise = ExerciseEntity(
            id = exerciseId,
            name = name,
            type = "reps",
            category = category,
            instructions = normalizedInstructions,
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            isUserCreated = true,
            accountId = session.accountId,
        )
        val muscleGroups = buildList {
            primaryMuscleGroupId?.let { muscleGroupId ->
                add(
                    ExerciseMuscleGroupEntity(
                        exerciseId = exerciseId,
                        muscleGroupId = muscleGroupId,
                        type = "primary",
                    ),
                )
            }
            distinctSecondaries.forEach { muscleGroupId ->
                add(
                    ExerciseMuscleGroupEntity(
                        exerciseId = exerciseId,
                        muscleGroupId = muscleGroupId,
                        type = "secondary",
                    ),
                )
            }
        }
        val equipment = equipmentId?.let { selectedEquipmentId ->
            listOf(
                ExerciseEquipmentEntity(
                    exerciseId = exerciseId,
                    equipmentId = selectedEquipmentId,
                    numberOfItems = null,
                ),
            )
        }.orEmpty()
        database.exerciseDao().insertCustomExercise(exercise, muscleGroups, equipment)
        it.captureEdit(
            AggregateType.CUSTOM_EXERCISE,
            exerciseId,
            MutationKind.CUSTOM_EXERCISE_UPSERT,
            changed = true,
        ) {
            MutationPayload.CustomExerciseUpsert(
                CustomExerciseInputModel(
                    id = exerciseId,
                    name = name,
                    primaryMuscleGroupId = primaryMuscleGroupId,
                    secondaryMuscleGroupIds = distinctSecondaries,
                    equipmentId = equipmentId,
                    equipmentNumItems = null,
                    category = category,
                    difficultyLevel = null,
                    forceType = null,
                    mechanics = null,
                    instructions = normalizedInstructions,
                ),
            )
        }
        exerciseId
    }

    override suspend fun updateCustomExercise(
        exerciseId: String,
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    ) {
        store.withFencedTransaction { transaction ->
            val current = database.exerciseDao().getScopedDetails(exerciseId, session.accountId)
                ?: return@withFencedTransaction
            if (!current.exercise.isUserCreated) return@withFencedTransaction
            if (name.isBlank()) throw SyncModelException("Custom exercise name must be nonblank")
            val normalizedInstructions = instructions.trim().takeIf { it.isNotEmpty() }?.let(::listOf)
            val distinctSecondaries = secondaryMuscleGroupIds
                .filter { it != primaryMuscleGroupId }
                .sorted()
            val newMusclePairs = buildSet {
                primaryMuscleGroupId?.let { add(it to "primary") }
                distinctSecondaries.forEach { add(it to "secondary") }
            }
            val currentMusclePairs = current.muscleGroups.map { it.id to it.relationshipType }.toSet()
            val currentEquipmentIds = current.equipment.map { it.id }.toSet()
            if (current.exercise.name == name &&
                current.exercise.category == category &&
                current.exercise.instructions == normalizedInstructions &&
                currentMusclePairs == newMusclePairs &&
                currentEquipmentIds == setOfNotNull(equipmentId)
            ) {
                // Secondary ordering alone (or any other no-op) creates no work.
                return@withFencedTransaction
            }
            // Retain imported metadata and quantity when the same equipment stays selected;
            // a changed equipment starts from the current UI null quantity.
            val retainedQuantity = current.equipment.singleOrNull()
                ?.takeIf { it.id == equipmentId }
                ?.numberOfItems
            val muscleGroups = buildList {
                primaryMuscleGroupId?.let { muscleGroupId ->
                    add(ExerciseMuscleGroupEntity(exerciseId, muscleGroupId, "primary"))
                }
                distinctSecondaries.forEach { muscleGroupId ->
                    add(ExerciseMuscleGroupEntity(exerciseId, muscleGroupId, "secondary"))
                }
            }
            val equipment = equipmentId?.let { selectedEquipmentId ->
                listOf(ExerciseEquipmentEntity(exerciseId, selectedEquipmentId, retainedQuantity))
            }.orEmpty()
            val updated = database.exerciseDao().updateCustomExerciseWithRelations(
                exerciseId = exerciseId,
                name = name,
                category = category,
                instructions = normalizedInstructions,
                muscleGroups = muscleGroups,
                equipment = equipment,
            )
            if (!updated) return@withFencedTransaction
            transaction.captureEdit(
                AggregateType.CUSTOM_EXERCISE,
                exerciseId,
                MutationKind.CUSTOM_EXERCISE_UPSERT,
                changed = true,
            ) {
                MutationPayload.CustomExerciseUpsert(
                    CustomExerciseInputModel(
                        id = exerciseId,
                        name = name,
                        primaryMuscleGroupId = primaryMuscleGroupId,
                        secondaryMuscleGroupIds = distinctSecondaries,
                        equipmentId = equipmentId,
                        equipmentNumItems = retainedQuantity,
                        category = category,
                        difficultyLevel = current.exercise.difficultyLevel,
                        forceType = current.exercise.forceType,
                        mechanics = current.exercise.mechanics,
                        instructions = normalizedInstructions,
                    ),
                )
            }
        }
    }

    override suspend fun deleteCustomExercise(exerciseId: String): Boolean =
        store.withFencedTransaction { transaction ->
            val current = database.exerciseDao().getScopedById(exerciseId, session.accountId)
                ?: return@withFencedTransaction false
            if (!current.isUserCreated) return@withFencedTransaction false
            val account = session.accountId
            if (account == null) {
                return@withFencedTransaction database.exerciseDao().deleteCustomExercise(exerciseId)
            }
            val references = database.workoutDao()
                .countRetainedWorkoutReferences(exerciseId, account) +
                database.routineDao().countRetainedRoutineReferences(exerciseId, account)
            if (references > 0) return@withFencedTransaction false
            database.exerciseDao().tombstoneCustomExercise(exerciseId, tombstoneClock())
            database.exerciseDao().deleteExerciseMuscleGroups(exerciseId)
            database.exerciseDao().deleteExerciseEquipment(exerciseId)
            transaction.captureEdit(
                AggregateType.CUSTOM_EXERCISE,
                exerciseId,
                MutationKind.AGGREGATE_DELETE,
                changed = true,
            ) { MutationPayload.AggregateDelete }
            true
        }

    /** Creates and persists an empty workout immediately, ready to be autosaved as it is edited. */
    override suspend fun startEmptyWorkout(startedAtMillis: Long): WorkoutEntity =
        store.withFencedTransaction { transaction ->
            val workout = WorkoutEntity(
                id = UUID.randomUUID().toString(),
                name = defaultWorkoutName(startedAtMillis),
                startedAtMillis = startedAtMillis,
                accountId = session.accountId,
            )
            val olds = replaceableActives()
            database.workoutDao().insertWorkout(workout)
            transaction.captureEdit(
                AggregateType.WORKOUT,
                workout.id,
                MutationKind.WORKOUT_START,
                changed = true,
                extraRoots = olds.map { ExtraAffectedRoot(AggregateType.WORKOUT, it.id) },
            ) {
                MutationPayload.WorkoutStart(workout.id, workout.name, workout.startedAtMillis)
            }
            removeOldActives(olds)
            database.workoutDao().getWorkout(workout.id)!!
        }

    /**
     * Every successful start atomically replaces all prior visible active workouts
     * in the selected scope: signed-out priors hard-delete, account priors become
     * hidden tombstones with children removed. Source resolution happens first so
     * invalid sources never disturb the old active.
     */
    private suspend fun replaceableActives(): List<WorkoutEntity> =
        database.workoutDao().getScopedActiveWorkouts(session.accountId)

    /**
     * Every exercise referenced by a copy source must be visible in the current
     * scope: bundled global or owned nondeleted custom rows. Hidden references
     * reject the whole source before any write.
     */
    private suspend fun allReferencesVisible(exerciseIds: List<String>): Boolean =
        exerciseIds.all { exerciseId ->
            database.exerciseDao().getScopedById(exerciseId, session.accountId) != null
        }

    /**
     * A set counts toward finishing only when it is marked complete with valid
     * sanitized inputs. Legacy completed rows with missing or negative values
     * can never become finished history.
     */
    private fun isValidCompletedSet(set: WorkoutSetEntity): Boolean =
        set.isCompleted &&
            sanitizeRepetitions(set.repetitions) != null &&
            sanitizeWeight(set.weightKg) != null

    private suspend fun removeOldActives(olds: List<WorkoutEntity>) {
        if (session.accountId == null) {
            olds.forEach { database.workoutDao().deleteWorkout(it.id) }
            return
        }
        olds.forEach { old ->
            database.workoutDao().deleteWorkoutExercises(old.id)
            database.workoutDao().tombstoneWorkout(old.id, tombstoneClock())
        }
    }

    override suspend fun activeWorkout() = store.withFencedTransaction {
        database.workoutDao().getScopedActiveWorkout(session.accountId)
    }

    override suspend fun workoutDetails(workoutId: String) = store.withFencedTransaction {
        database.workoutDao().getScopedWorkoutDetails(workoutId, session.accountId)
    }

    suspend fun workouts() = store.withFencedTransaction {
        database.workoutDao().getScopedAllWorkouts(session.accountId)
    }

    override suspend fun finishedWorkoutSummaries() = store.withFencedTransaction {
        database.workoutDao().getScopedFinishedWorkoutSummaries(session.accountId)
    }

    override suspend fun createRoutineDraft(): RoutineEntity = store.withFencedTransaction { transaction ->
        val routine = RoutineEntity(
            id = UUID.randomUUID().toString(),
            name = "",
            isDraft = true,
            accountId = session.accountId,
        )
        database.routineDao().insertRoutine(routine)
        captureRoutineUpsert(transaction, routine.id)
        database.routineDao().getRoutine(routine.id)!!
    }

    override suspend fun routineDetails(routineId: String) = store.withFencedTransaction {
        database.routineDao().getScopedRoutineDetails(routineId, session.accountId)
    }

    suspend fun savedRoutines() = store.withFencedTransaction {
        database.routineDao().getScopedSavedRoutines(session.accountId)
    }

    override suspend fun savedRoutineDetails() = store.withFencedTransaction {
        database.routineDao().getScopedSavedRoutineDetails(session.accountId)
    }

    override suspend fun saveRoutine(routineId: String, name: String) =
        store.withFencedTransaction { transaction ->
            val current = database.routineDao().getScopedRoutine(routineId, session.accountId)
                ?: return@withFencedTransaction
            if (name.isBlank()) return@withFencedTransaction
            if (database.routineDao().getRoutineExercises(routineId).isEmpty()) {
                return@withFencedTransaction
            }
            if (!current.isDraft && current.name == name) return@withFencedTransaction
            database.routineDao().saveRoutine(routineId, name)
            transaction.captureEdit(
                AggregateType.ROUTINE,
                routineId,
                MutationKind.ROUTINE_SAVE,
                changed = true,
            ) { MutationPayload.RoutineSave(name) }
        }

    override suspend fun updateRoutineName(routineId: String, name: String) =
        store.withFencedTransaction { transaction ->
            val current = database.routineDao().getScopedRoutine(routineId, session.accountId)
                ?: return@withFencedTransaction
            if (current.name == name) return@withFencedTransaction
            database.routineDao().updateRoutineName(routineId, name)
            captureRoutineUpsert(transaction, routineId)
        }

    override suspend fun deleteRoutine(routineId: String) =
        store.withFencedTransaction { transaction ->
            database.routineDao().getScopedRoutine(routineId, session.accountId)
                ?: return@withFencedTransaction
            if (session.accountId == null) {
                database.routineDao().deleteRoutine(routineId)
                return@withFencedTransaction
            }
            database.routineDao().deleteRoutineExercises(routineId)
            database.routineDao().tombstoneRoutine(routineId, tombstoneClock())
            transaction.captureEdit(
                AggregateType.ROUTINE,
                routineId,
                MutationKind.AGGREGATE_DELETE,
                changed = true,
            ) { MutationPayload.AggregateDelete }
        }

    /**
     * Captures the complete current routine aggregate from raw internal details.
     * Raw reads keep every persisted child for the upload; scoped root verification
     * happens before this call.
     */
    private suspend fun captureRoutineUpsert(
        transaction: SyncTransaction,
        routineId: String,
    ) {
        val details = database.routineDao().getRoutineDetails(routineId)
            ?: throw SyncModelException("Routine $routineId not found for capture")
        transaction.captureEdit(
            AggregateType.ROUTINE,
            routineId,
            MutationKind.ROUTINE_UPSERT,
            changed = true,
        ) { MutationPayload.RoutineUpsert(details.toRoutineInput()) }
    }

    /** Saves a finished workout's exercise plan as a reusable routine. */
    override suspend fun saveWorkoutAsRoutine(workoutId: String, routineName: String): RoutineEntity? =
        store.withFencedTransaction { transaction ->
            if (routineName.isBlank()) return@withFencedTransaction null
            val scoped = database.workoutDao().getScopedWorkout(workoutId, session.accountId)
                ?: return@withFencedTransaction null
            if (scoped.status != "finished") return@withFencedTransaction null
            val workout = database.workoutDao().getWorkoutDetails(workoutId)
                ?: return@withFencedTransaction null
            // An empty finished source cannot seed a saved routine, and every
            // referenced exercise must be visible in the current scope.
            if (workout.exercises.isEmpty()) return@withFencedTransaction null
            if (!allReferencesVisible(workout.exercises.map { it.workoutExercise.exerciseId })) {
                return@withFencedTransaction null
            }

            val routine = RoutineEntity(
                id = UUID.randomUUID().toString(),
                name = routineName,
                accountId = session.accountId,
            )
            val exercises = workout.exercises.mapIndexed { position, workoutExercise ->
                RoutineExerciseEntity(
                    id = UUID.randomUUID().toString(),
                    routineId = routine.id,
                    exerciseId = workoutExercise.workoutExercise.exerciseId,
                    position = position,
                    restDurationSeconds = workoutExercise.workoutExercise.restDurationSeconds,
                )
            }
            val perExerciseSets = workout.exercises.mapIndexed { exerciseIndex, workoutExercise ->
                workoutExercise.sets.mapIndexed { position, workoutSet ->
                    RoutineSetEntity(
                        id = UUID.randomUUID().toString(),
                        routineExerciseId = exercises[exerciseIndex].id,
                        position = position,
                        repetitions = workoutSet.repetitions,
                        weightKg = workoutSet.weightKg,
                    )
                }
            }
            val bindings = workout.exercises.mapIndexed { exerciseIndex, workoutExercise ->
                ChildExerciseBinding(
                    sourceExerciseId = workoutExercise.workoutExercise.id,
                    id = exercises[exerciseIndex].id,
                    sets = workoutExercise.sets.mapIndexed { setIndex, workoutSet ->
                        ChildSetBinding(
                            sourceSetId = workoutSet.id,
                            id = perExerciseSets[exerciseIndex][setIndex].id,
                        )
                    },
                )
            }
            database.routineDao().insertRoutineWithExercisesAndSets(
                routine,
                exercises,
                perExerciseSets.flatten(),
            )
            transaction.captureEdit(
                AggregateType.ROUTINE,
                routine.id,
                MutationKind.WORKOUT_SAVE_AS_ROUTINE,
                changed = true,
            ) {
                MutationPayload.WorkoutSaveAsRoutine(
                    sourceWorkoutId = workoutId,
                    id = routine.id,
                    name = routineName,
                    childIds = bindings,
                )
            }
            database.routineDao().getRoutine(routine.id)!!
        }

    /** Creates an active workout from a saved routine's exercises, sets, and rest durations. */
    override suspend fun startWorkoutFromRoutine(
        routineId: String,
        startedAtMillis: Long,
    ): WorkoutEntity? = store.withFencedTransaction { transaction ->
        val scoped = database.routineDao().getScopedRoutine(routineId, session.accountId)
            ?: return@withFencedTransaction null
        if (scoped.isDraft) return@withFencedTransaction null
        // Temporary blank/empty autosaves stay editable but cannot start.
        if (scoped.name.isBlank()) return@withFencedTransaction null
        val routine = database.routineDao().getRoutineDetails(routineId)
            ?: return@withFencedTransaction null
        if (routine.exercises.isEmpty()) return@withFencedTransaction null
        if (!allReferencesVisible(routine.exercises.map { it.routineExercise.exerciseId })) {
            return@withFencedTransaction null
        }

        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = routine.routine.name,
            startedAtMillis = startedAtMillis,
            accountId = session.accountId,
        )
        val workoutExercises = routine.exercises.mapIndexed { position, routineExercise ->
            WorkoutExerciseEntity(
                id = UUID.randomUUID().toString(),
                workoutId = workout.id,
                exerciseId = routineExercise.routineExercise.exerciseId,
                position = position,
                restDurationSeconds = routineExercise.routineExercise.restDurationSeconds,
            )
        }
        val perExerciseSets = routine.exercises.mapIndexed { exerciseIndex, routineExercise ->
            routineExercise.sets.mapIndexed { position, routineSet ->
                WorkoutSetEntity(
                    id = UUID.randomUUID().toString(),
                    workoutExerciseId = workoutExercises[exerciseIndex].id,
                    position = position,
                    repetitions = routineSet.repetitions,
                    weightKg = routineSet.weightKg,
                )
            }
        }
        val bindings = routine.exercises.mapIndexed { exerciseIndex, routineExercise ->
            ChildExerciseBinding(
                sourceExerciseId = routineExercise.routineExercise.id,
                id = workoutExercises[exerciseIndex].id,
                sets = routineExercise.sets.mapIndexed { setIndex, routineSet ->
                    ChildSetBinding(
                        sourceSetId = routineSet.id,
                        id = perExerciseSets[exerciseIndex][setIndex].id,
                    )
                },
            )
        }
        val olds = replaceableActives()
        database.workoutDao().insertWorkoutWithExercisesAndSets(
            workout,
            workoutExercises,
            perExerciseSets.flatten(),
        )
        transaction.captureEdit(
            AggregateType.WORKOUT,
            workout.id,
            MutationKind.ROUTINE_START_WORKOUT,
            changed = true,
            extraRoots = olds.map { ExtraAffectedRoot(AggregateType.WORKOUT, it.id) },
        ) {
            MutationPayload.RoutineStartWorkout(
                sourceRoutineId = routineId,
                id = workout.id,
                startedAtMillis = startedAtMillis,
                childIds = bindings,
            )
        }
        removeOldActives(olds)
        database.workoutDao().getWorkout(workout.id)!!
    }

    override suspend fun addExercisesToRoutine(routineId: String, exerciseIds: Collection<String>) =
        store.withFencedTransaction { transaction ->
            if (exerciseIds.isEmpty()) return@withFencedTransaction
            database.routineDao().getScopedRoutine(routineId, session.accountId)
                ?: return@withFencedTransaction
            val visibleIds = exerciseIds.filter { exerciseId ->
                database.exerciseDao().getScopedById(exerciseId, session.accountId) != null
            }
            // All-or-nothing selection: any invalid reference rejects the entire
            // selection with a typed failure before inserts (established rollback
            // semantics); foreign/deleted parent calls stay silent no-ops above.
            if (visibleIds.size != exerciseIds.size) {
                throw SyncModelException("Unknown exercise reference in routine selection")
            }
            val routineDao = database.routineDao()
            val firstPosition = routineDao.getRoutineExercises(routineId).size
            val exercises = visibleIds.mapIndexed { index, exerciseId ->
                RoutineExerciseEntity(
                    id = UUID.randomUUID().toString(),
                    routineId = routineId,
                    exerciseId = exerciseId,
                    position = firstPosition + index,
                )
            }
            val sets = exercises.map { exercise ->
                RoutineSetEntity(
                    id = UUID.randomUUID().toString(),
                    routineExerciseId = exercise.id,
                    position = 0,
                )
            }
            routineDao.insertExercisesWithInitialSets(exercises, sets)
            captureRoutineUpsert(transaction, routineId)
        }

    override suspend fun addSetToRoutineExercise(
        routineExerciseId: String,
        position: Int,
        repetitions: Int?,
        weightKg: Double?,
    ) = store.withFencedTransaction { transaction ->
        val parent = database.routineDao().getRoutineExercise(routineExerciseId)
            ?: return@withFencedTransaction
        database.routineDao().getScopedRoutine(parent.routineId, session.accountId)
            ?: return@withFencedTransaction
        // The appended position always follows the current siblings in-transaction.
        val actualPosition = database.routineDao().getRoutineSets(routineExerciseId).size
        database.routineDao().insertRoutineSets(
            listOf(
                RoutineSetEntity(
                    id = UUID.randomUUID().toString(),
                    routineExerciseId = routineExerciseId,
                    position = actualPosition,
                    repetitions = sanitizeRepetitions(repetitions),
                    weightKg = sanitizeWeight(weightKg),
                ),
            ),
        )
        captureRoutineUpsert(transaction, parent.routineId)
    }

    override suspend fun updateRoutineSet(set: RoutineSetEntity) =
        store.withFencedTransaction { transaction ->
            val actual = database.routineDao().getRoutineSet(set.id) ?: return@withFencedTransaction
            if (actual.routineExerciseId != set.routineExerciseId) return@withFencedTransaction
            val parent = database.routineDao().getRoutineExercise(actual.routineExerciseId)
                ?: return@withFencedTransaction
            database.routineDao().getScopedRoutine(parent.routineId, session.accountId)
                ?: return@withFencedTransaction
            // Only editable values apply; the stored position, ID, and parent win over
            // any stale caller-supplied entity, so obsolete rows can never reparent.
            val repetitions = sanitizeRepetitions(set.repetitions)
            val weightKg = sanitizeWeight(set.weightKg)
            if (actual.repetitions == repetitions && actual.weightKg == weightKg) {
                return@withFencedTransaction
            }
            database.routineDao().updateRoutineSetValues(actual.id, repetitions, weightKg)
            captureRoutineUpsert(transaction, parent.routineId)
        }

    override suspend fun updateRoutineExerciseRestDuration(routineExerciseId: String, seconds: Int) =
        store.withFencedTransaction { transaction ->
            val actual = database.routineDao().getRoutineExercise(routineExerciseId)
                ?: return@withFencedTransaction
            database.routineDao().getScopedRoutine(actual.routineId, session.accountId)
                ?: return@withFencedTransaction
            val restDurationSeconds = seconds.coerceAtLeast(0)
            if (actual.restDurationSeconds == restDurationSeconds) return@withFencedTransaction
            database.routineDao().updateRestDuration(routineExerciseId, restDurationSeconds)
            captureRoutineUpsert(transaction, actual.routineId)
        }

    override suspend fun deleteRoutineExercise(routineExerciseId: String, routineId: String) =
        store.withFencedTransaction { transaction ->
            val actual = database.routineDao().getRoutineExercise(routineExerciseId)
                ?: return@withFencedTransaction
            if (actual.routineId != routineId) return@withFencedTransaction
            database.routineDao().getScopedRoutine(actual.routineId, session.accountId)
                ?: return@withFencedTransaction
            database.routineDao().deleteRoutineExerciseAndReorder(actual.id, actual.routineId)
            transaction.captureEdit(
                AggregateType.ROUTINE,
                actual.routineId,
                MutationKind.ROUTINE_DELETE_EXERCISE,
                changed = true,
            ) { MutationPayload.RoutineDeleteExercise(actual.id) }
        }

    override suspend fun deleteRoutineSet(set: RoutineSetEntity) =
        store.withFencedTransaction { transaction ->
            val actual = database.routineDao().getRoutineSet(set.id) ?: return@withFencedTransaction
            if (actual.routineExerciseId != set.routineExerciseId) return@withFencedTransaction
            val parent = database.routineDao().getRoutineExercise(actual.routineExerciseId)
                ?: return@withFencedTransaction
            database.routineDao().getScopedRoutine(parent.routineId, session.accountId)
                ?: return@withFencedTransaction
            database.routineDao().deleteRoutineSetAndReorder(actual)
            transaction.captureEdit(
                AggregateType.ROUTINE,
                parent.routineId,
                MutationKind.ROUTINE_DELETE_SET,
                changed = true,
            ) { MutationPayload.RoutineDeleteSet(actual.id) }
        }

    /**
     * Creates an active copy of a saved workout. Exercise configuration and set inputs are
     * preserved, while every copied set starts unfinished with no recorded rest time.
     */
    override suspend fun performWorkoutAgain(workoutId: String, startedAtMillis: Long): WorkoutEntity? =
        store.withFencedTransaction { transaction ->
            val scoped = database.workoutDao().getScopedWorkout(workoutId, session.accountId)
                ?: return@withFencedTransaction null
            if (scoped.status != "finished") return@withFencedTransaction null
            val source = database.workoutDao().getWorkoutDetails(workoutId)
                ?: return@withFencedTransaction null
            if (source.exercises.isEmpty()) return@withFencedTransaction null
            if (!allReferencesVisible(source.exercises.map { it.workoutExercise.exerciseId })) {
                return@withFencedTransaction null
            }
            val workout = WorkoutEntity(
                id = UUID.randomUUID().toString(),
                name = source.workout.name,
                startedAtMillis = startedAtMillis,
                accountId = session.accountId,
            )
            val workoutExercises = source.exercises.mapIndexed { position, sourceExercise ->
                WorkoutExerciseEntity(
                    id = UUID.randomUUID().toString(),
                    workoutId = workout.id,
                    exerciseId = sourceExercise.workoutExercise.exerciseId,
                    position = position,
                    notes = sourceExercise.workoutExercise.notes,
                    restDurationSeconds = sourceExercise.workoutExercise.restDurationSeconds,
                )
            }
            val perExerciseSets = source.exercises.mapIndexed { exerciseIndex, sourceExercise ->
                val workoutExercise = workoutExercises[exerciseIndex]
                sourceExercise.sets.mapIndexed { position, sourceSet ->
                    WorkoutSetEntity(
                        id = UUID.randomUUID().toString(),
                        workoutExerciseId = workoutExercise.id,
                        position = position,
                        repetitions = sourceSet.repetitions,
                        weightKg = sourceSet.weightKg,
                    )
                }
            }
            val bindings = source.exercises.mapIndexed { exerciseIndex, sourceExercise ->
                ChildExerciseBinding(
                    sourceExerciseId = sourceExercise.workoutExercise.id,
                    id = workoutExercises[exerciseIndex].id,
                    sets = sourceExercise.sets.mapIndexed { setIndex, sourceSet ->
                        ChildSetBinding(
                            sourceSetId = sourceSet.id,
                            id = perExerciseSets[exerciseIndex][setIndex].id,
                        )
                    },
                )
            }
            val olds = replaceableActives()
            database.workoutDao().insertWorkoutWithExercisesAndSets(
                workout,
                workoutExercises,
                perExerciseSets.flatten(),
            )
            transaction.captureEdit(
                AggregateType.WORKOUT,
                workout.id,
                MutationKind.WORKOUT_PERFORM_AGAIN,
                changed = true,
                extraRoots = olds.map { ExtraAffectedRoot(AggregateType.WORKOUT, it.id) },
            ) {
                MutationPayload.WorkoutPerformAgain(
                    sourceWorkoutId = workoutId,
                    id = workout.id,
                    startedAtMillis = startedAtMillis,
                    childIds = bindings,
                )
            }
            removeOldActives(olds)
            database.workoutDao().getWorkout(workout.id)!!
        }

    override suspend fun addSetToWorkoutExercise(
        workoutExerciseId: String,
        position: Int,
        repetitions: Int?,
        weightKg: Double?,
    ): WorkoutSetEntity = store.withFencedTransaction { transaction ->
        val parent = database.workoutDao().getWorkoutExercise(workoutExerciseId)
            ?: throw SyncModelException("Workout exercise $workoutExerciseId not found for addSet")
        if (scopedActiveWorkout(parent.workoutId) == null) {
            throw SyncModelException("Workout ${parent.workoutId} is not an editable active workout")
        }
        // The appended position always follows the current siblings in-transaction.
        val actualPosition = database.workoutDao().getWorkoutSets(workoutExerciseId).size
        val workoutSet = WorkoutSetEntity(
            id = UUID.randomUUID().toString(),
            workoutExerciseId = workoutExerciseId,
            position = actualPosition,
            repetitions = sanitizeRepetitions(repetitions),
            weightKg = sanitizeWeight(weightKg),
        )
        database.workoutDao().insertWorkoutSet(workoutSet)
        captureWorkoutUpsert(transaction, parent.workoutId)
        workoutSet
    }

    override suspend fun addExercisesToWorkout(workoutId: String, exerciseIds: Collection<String>) =
        store.withFencedTransaction { transaction ->
            if (exerciseIds.isEmpty()) return@withFencedTransaction
            if (scopedActiveWorkout(workoutId) == null) return@withFencedTransaction
            // Resolve every selected reference before any write: the whole
            // selection (including repeats) is rejected when any ref is invisible.
            exerciseIds.forEach { exerciseId ->
                if (database.exerciseDao().getScopedById(exerciseId, session.accountId) == null) {
                    throw SyncModelException("Unknown exercise reference in workout addition")
                }
            }
            val workoutDao = database.workoutDao()
            val firstPosition = workoutDao.getWorkoutExercises(workoutId).size
            val additions = mutableListOf<ExerciseAddition>()
            val newExercises = mutableListOf<WorkoutExerciseEntity>()
            val newSets = mutableListOf<WorkoutSetEntity>()
            exerciseIds.forEachIndexed { index, exerciseId ->
                val entry = WorkoutExerciseEntity(
                    id = UUID.randomUUID().toString(),
                    workoutId = workoutId,
                    exerciseId = exerciseId,
                    position = firstPosition + index,
                    restDurationSeconds =
                        workoutDao.previousScopedRestDurationForExercise(exerciseId, session.accountId)
                            ?: DEFAULT_REST_DURATION_SECONDS,
                )
                val history = workoutDao.previousScopedCompletedSetsForExercise(exerciseId, session.accountId)
                val sets = if (history.isEmpty()) {
                    listOf(
                        WorkoutSetEntity(
                            id = UUID.randomUUID().toString(),
                            workoutExerciseId = entry.id,
                            position = 0,
                        ),
                    )
                } else {
                    history.mapIndexed { position, previousSet ->
                        WorkoutSetEntity(
                            id = UUID.randomUUID().toString(),
                            workoutExerciseId = entry.id,
                            position = position,
                            repetitions = previousSet.repetitions,
                            weightKg = previousSet.weightKg,
                        )
                    }
                }
                newExercises.add(entry)
                newSets.addAll(sets)
                additions.add(
                    ExerciseAddition(
                        id = entry.id,
                        exerciseId = exerciseId,
                        setIds = sets.map { it.id },
                    ),
                )
            }
            workoutDao.insertExercisesWithInitialSets(newExercises, newSets)
            transaction.captureEdit(
                AggregateType.WORKOUT,
                workoutId,
                MutationKind.WORKOUT_ADD_EXERCISES,
                changed = true,
            ) { MutationPayload.WorkoutAddExercises(additions) }
        }

    /**
     * Returns whether this fenced write actually transitioned an editable set
     * from incomplete to complete. All no-ops, guards, invalidations, and
     * ordinary already-completed edits return false, so callers never start a
     * timer for a set that was already completed history. Normalization,
     * ownership, and capture semantics are unchanged.
     */
    override suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity): Boolean =
        store.withFencedTransaction { transaction ->
            val actual = database.workoutDao().getWorkoutSet(workoutSet.id)
                ?: return@withFencedTransaction false
            if (actual.workoutExerciseId != workoutSet.workoutExerciseId) {
                return@withFencedTransaction false
            }
            val parent = database.workoutDao().getWorkoutExercise(actual.workoutExerciseId)
                ?: return@withFencedTransaction false
            if (scopedActiveWorkout(parent.workoutId) == null) return@withFencedTransaction false
            // Only editable values apply; the stored parent, ID, and position win over
            // any stale caller-supplied entity, so obsolete rows can never reparent.
            val repetitions = sanitizeRepetitions(workoutSet.repetitions)
            val weightKg = sanitizeWeight(workoutSet.weightKg)
            // Completion is requested AND valid: the caller flag decides, but valid
            // sanitized inputs are necessary — invalid inputs always force incomplete.
            val isCompleted = workoutSet.isCompleted && repetitions != null && weightKg != null
            // Ordinary UI edits never overwrite timer-recorded rest: staying complete
            // preserves the stored value, every other transition starts from null.
            val restSeconds = if (isCompleted && actual.isCompleted) actual.restSeconds else null
            if (actual.repetitions == repetitions && actual.weightKg == weightKg &&
                actual.isCompleted == isCompleted && actual.restSeconds == restSeconds
            ) {
                return@withFencedTransaction false
            }
            database.workoutDao().updateWorkoutSetValues(
                actual.id,
                repetitions,
                weightKg,
                isCompleted,
                restSeconds,
            )
            captureWorkoutUpsert(transaction, parent.workoutId)
            !actual.isCompleted && isCompleted
        }

    override suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int) =
        store.withFencedTransaction { transaction ->
            val actual = database.workoutDao().getWorkoutSet(workoutSetId)
                ?: return@withFencedTransaction
            val parent = database.workoutDao().getWorkoutExercise(actual.workoutExerciseId)
                ?: return@withFencedTransaction
            if (scopedActiveWorkout(parent.workoutId) == null) return@withFencedTransaction
            val restSeconds = elapsedSeconds.coerceAtLeast(0)
            if (actual.restSeconds == restSeconds) return@withFencedTransaction
            database.workoutDao().updateWorkoutSetRestSeconds(workoutSetId, restSeconds)
            captureWorkoutUpsert(transaction, parent.workoutId)
        }

    override suspend fun updateWorkoutName(workoutId: String, name: String) =
        store.withFencedTransaction { transaction ->
            val current = scopedActiveWorkout(workoutId) ?: return@withFencedTransaction
            if (current.name == name) return@withFencedTransaction
            database.workoutDao().updateWorkoutName(workoutId, name)
            captureWorkoutUpsert(transaction, workoutId)
        }

    override suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String) =
        store.withFencedTransaction { transaction ->
            val actual = database.workoutDao().getWorkoutExercise(workoutExerciseId)
                ?: return@withFencedTransaction
            if (scopedActiveWorkout(actual.workoutId) == null) return@withFencedTransaction
            if (actual.notes == notes) return@withFencedTransaction
            database.workoutDao().updateWorkoutExerciseNotes(workoutExerciseId, notes)
            captureWorkoutUpsert(transaction, actual.workoutId)
        }

    override suspend fun updateWorkoutExerciseRestDuration(
        workoutExerciseId: String,
        restDurationSeconds: Int,
    ) = store.withFencedTransaction { transaction ->
        val actual = database.workoutDao().getWorkoutExercise(workoutExerciseId)
            ?: return@withFencedTransaction
        if (scopedActiveWorkout(actual.workoutId) == null) return@withFencedTransaction
        val rest = restDurationSeconds.coerceAtLeast(0)
        if (actual.restDurationSeconds == rest) return@withFencedTransaction
        database.workoutDao().updateWorkoutExerciseRestDuration(workoutExerciseId, rest)
        captureWorkoutUpsert(transaction, actual.workoutId)
    }

    /** Replaces only the catalogue exercise, keeping this workout entry and its sets intact. */
    override suspend fun replaceWorkoutExercise(workoutExerciseId: String, exerciseId: String) =
        store.withFencedTransaction { transaction ->
            val actual = database.workoutDao().getWorkoutExercise(workoutExerciseId)
                ?: return@withFencedTransaction
            if (scopedActiveWorkout(actual.workoutId) == null) return@withFencedTransaction
            if (actual.exerciseId == exerciseId) return@withFencedTransaction
            if (database.exerciseDao().getScopedById(exerciseId, session.accountId) == null) {
                throw SyncModelException("Unknown exercise reference in workout replacement")
            }
            database.workoutDao().replaceWorkoutExercise(workoutExerciseId, exerciseId)
            transaction.captureEdit(
                AggregateType.WORKOUT,
                actual.workoutId,
                MutationKind.WORKOUT_REPLACE_EXERCISE,
                changed = true,
            ) { MutationPayload.WorkoutReplaceExercise(actual.id, exerciseId) }
        }

    /**
     * Finishes a workout only when it holds at least one valid completed set:
     * completed with sanitized non-null repetitions and weight. Unfinished sets,
     * invalid completed sets, and newly empty exercises are removed; surviving
     * exercises AND sets are renumbered consecutively with IDs, inputs, notes,
     * and both rests preserved. Returns whether the workout was finished.
     */
    override suspend fun finishWorkout(
        workoutId: String,
        finishedAtMillis: Long,
    ): Boolean = store.withFencedTransaction { transaction ->
        if (scopedActiveWorkout(workoutId) == null) return@withFencedTransaction false
        val workoutDao = database.workoutDao()
        val exercises = workoutDao.getWorkoutExercises(workoutId)
        val setsByExercise = exercises.associateWith { entry ->
            workoutDao.getWorkoutSets(entry.id)
        }
        val survivorsByExercise = setsByExercise.mapValues { (_, sets) ->
            sets.filter(::isValidCompletedSet)
        }
        if (survivorsByExercise.values.all { it.isEmpty() }) return@withFencedTransaction false
        val doomedSetIds = setsByExercise.values.flatten()
            .filterNot(::isValidCompletedSet)
            .map { it.id }
        // Child counts are unbounded, so delete in bounded batches instead of
        // one IN statement that can exceed older SQLite bind limits.
        doomedSetIds.chunked(500).forEach { batch ->
            workoutDao.deleteWorkoutSetsByIds(batch)
        }
        val survivingEntries = exercises.filter { survivorsByExercise.getValue(it).isNotEmpty() }
        exercises.filterNot { survivorsByExercise.getValue(it).isNotEmpty() }.forEach { entry ->
            workoutDao.deleteWorkoutExercise(entry.id)
        }
        survivingEntries.forEachIndexed { position, entry ->
            if (entry.position != position) workoutDao.updateWorkoutExercisePosition(entry.id, position)
            survivorsByExercise.getValue(entry).forEachIndexed { setPosition, set ->
                if (set.position != setPosition) {
                    workoutDao.updateWorkoutSetPosition(set.id, setPosition)
                }
            }
        }
        workoutDao.finishWorkout(workoutId, finishedAtMillis)
        transaction.captureEdit(
            AggregateType.WORKOUT,
            workoutId,
            MutationKind.WORKOUT_FINISH,
            changed = true,
        ) { MutationPayload.WorkoutFinish(finishedAtMillis) }
        true
    }

    override suspend fun hasCompletedSets(workoutId: String): Boolean =
        store.withFencedTransaction {
            if (scopedActiveWorkout(workoutId) == null) return@withFencedTransaction false
            database.workoutDao().getWorkoutExercises(workoutId).any { entry ->
                database.workoutDao().getWorkoutSets(entry.id).any(::isValidCompletedSet)
            }
        }

    /** Discarding a finished or active workout removes it; account roots tombstone. */
    override suspend fun discardWorkout(workoutId: String) = deleteWorkout(workoutId)

    override suspend fun deleteWorkout(workoutId: String) =
        store.withFencedTransaction { transaction ->
            database.workoutDao().getScopedWorkout(workoutId, session.accountId)
                ?: return@withFencedTransaction
            if (session.accountId == null) {
                database.workoutDao().deleteWorkout(workoutId)
                return@withFencedTransaction
            }
            database.workoutDao().deleteWorkoutExercises(workoutId)
            database.workoutDao().tombstoneWorkout(workoutId, tombstoneClock())
            transaction.captureEdit(
                AggregateType.WORKOUT,
                workoutId,
                MutationKind.AGGREGATE_DELETE,
                changed = true,
            ) { MutationPayload.AggregateDelete }
        }

    override suspend fun deleteExerciseFromWorkout(workoutExerciseId: String, workoutId: String) =
        store.withFencedTransaction { transaction ->
            val actual = database.workoutDao().getWorkoutExercise(workoutExerciseId)
                ?: return@withFencedTransaction
            if (actual.workoutId != workoutId) return@withFencedTransaction
            if (scopedActiveWorkout(actual.workoutId) == null) return@withFencedTransaction
            database.workoutDao().deleteWorkoutExerciseAndReorder(actual.id, actual.workoutId)
            transaction.captureEdit(
                AggregateType.WORKOUT,
                actual.workoutId,
                MutationKind.WORKOUT_DELETE_EXERCISE,
                changed = true,
            ) { MutationPayload.WorkoutDeleteExercise(actual.id) }
        }

    override suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity) =
        store.withFencedTransaction { transaction ->
            val actual = database.workoutDao().getWorkoutSet(workoutSet.id)
                ?: return@withFencedTransaction
            if (actual.workoutExerciseId != workoutSet.workoutExerciseId) {
                return@withFencedTransaction
            }
            val parent = database.workoutDao().getWorkoutExercise(actual.workoutExerciseId)
                ?: return@withFencedTransaction
            if (scopedActiveWorkout(parent.workoutId) == null) return@withFencedTransaction
            database.workoutDao().deleteWorkoutSetAndReorder(actual)
            transaction.captureEdit(
                AggregateType.WORKOUT,
                parent.workoutId,
                MutationKind.WORKOUT_DELETE_SET,
                changed = true,
            ) { MutationPayload.WorkoutDeleteSet(actual.id) }
        }

    /**
     * Owned visible ACTIVE parent verification for simple-workout edits. Finished
     * history is immutable and wrong-scope, deleted, or missing roots no-op.
     */
    private suspend fun scopedActiveWorkout(workoutId: String): WorkoutEntity? {
        val workout = database.workoutDao().getScopedWorkout(workoutId, session.accountId)
            ?: return null
        if (workout.status != "active") return null
        return workout
    }

    /**
     * Captures the complete current workout aggregate from raw internal details.
     * Raw reads keep every persisted child for the upload; scoped active-parent
     * verification happens before this call.
     */
    private suspend fun captureWorkoutUpsert(
        transaction: SyncTransaction,
        workoutId: String,
    ) {
        val details = database.workoutDao().getWorkoutDetails(workoutId)
            ?: throw SyncModelException("Workout $workoutId not found for capture")
        transaction.captureEdit(
            AggregateType.WORKOUT,
            workoutId,
            MutationKind.WORKOUT_UPSERT,
            changed = true,
        ) { MutationPayload.WorkoutUpsert(details.toWorkoutInput()) }
    }

}
