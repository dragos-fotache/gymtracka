package com.dragosfotache.gymtracka.data.gym

import android.content.Context
import java.util.UUID

/** Application-facing entry point for catalogue, workout, and routine persistence. */
internal class GymRepository(
    private val database: GymDatabase,
) : ExerciseStateRepository,
    GymAppStateRepository,
    ActiveWorkoutStateRepository,
    RoutineStateRepository,
    WorkoutHistoryStateRepository {
    /** Production construction path. Tests can inject an isolated [GymDatabase] directly. */
    constructor(context: Context) : this(GymDatabase.getInstance(context))

    private companion object {
        const val DEFAULT_REST_DURATION_SECONDS = 30
    }

    override suspend fun muscleGroups() = database.muscleGroupDao().getAll()
    suspend fun muscleGroup(id: String) = database.muscleGroupDao().getById(id)
    suspend fun muscleGroupBySlug(slug: String) = database.muscleGroupDao().getBySlug(slug)

    override suspend fun equipment() = database.equipmentDao().getAll()
    suspend fun equipment(id: String) = database.equipmentDao().getById(id)

    suspend fun exercises() = database.exerciseDao().getAll()
    override suspend fun exerciseCategories() = database.exerciseDao().getCategories()
    override suspend fun exerciseSummaries() = database.exerciseDao().getAllSummaries()
    suspend fun exercise(id: String) = database.exerciseDao().getById(id)
    suspend fun searchExercises(query: String) = database.exerciseDao().searchByName(query)
    suspend fun exercisesForMuscleGroup(muscleGroupId: String) =
        database.exerciseDao().getForMuscleGroup(muscleGroupId)

    override suspend fun exerciseDetails(exerciseId: String) = database.exerciseDao().getDetails(exerciseId)

    override suspend fun createCustomExercise(
        name: String,
        primaryMuscleGroupId: String?,
        secondaryMuscleGroupIds: Set<String>,
        equipmentId: String?,
        category: String?,
        instructions: String,
    ): String {
        val exerciseId = UUID.randomUUID().toString()
        val exercise = ExerciseEntity(
            id = exerciseId,
            name = name,
            type = "reps",
            category = category,
            instructions = instructions.trim().takeIf { it.isNotEmpty() }?.let(::listOf),
            difficultyLevel = null,
            forceType = null,
            mechanics = null,
            isUserCreated = true,
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
            secondaryMuscleGroupIds.forEach { muscleGroupId ->
                if (muscleGroupId != primaryMuscleGroupId) {
                    add(
                        ExerciseMuscleGroupEntity(
                            exerciseId = exerciseId,
                            muscleGroupId = muscleGroupId,
                            type = "secondary",
                        ),
                    )
                }
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
        return exerciseId
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
        val muscleGroups = buildList {
            primaryMuscleGroupId?.let { muscleGroupId ->
                add(ExerciseMuscleGroupEntity(exerciseId, muscleGroupId, "primary"))
            }
            secondaryMuscleGroupIds.forEach { muscleGroupId ->
                if (muscleGroupId != primaryMuscleGroupId) {
                    add(ExerciseMuscleGroupEntity(exerciseId, muscleGroupId, "secondary"))
                }
            }
        }
        val equipment = equipmentId?.let { selectedEquipmentId ->
            listOf(ExerciseEquipmentEntity(exerciseId, selectedEquipmentId, null))
        }.orEmpty()
        database.exerciseDao().updateCustomExerciseWithRelations(
            exerciseId = exerciseId,
            name = name,
            category = category,
            instructions = instructions.trim().takeIf { it.isNotEmpty() }?.let(::listOf),
            muscleGroups = muscleGroups,
            equipment = equipment,
        )
    }

    override suspend fun deleteCustomExercise(exerciseId: String) =
        database.exerciseDao().deleteCustomExercise(exerciseId)

    /** Creates and persists an empty workout immediately, ready to be autosaved as it is edited. */
    override suspend fun startEmptyWorkout(startedAtMillis: Long): WorkoutEntity {
        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = defaultWorkoutName(startedAtMillis),
            startedAtMillis = startedAtMillis,
        )
        database.workoutDao().insertWorkout(workout)
        return workout
    }

    override suspend fun activeWorkout() = database.workoutDao().getActiveWorkout()
    override suspend fun workoutDetails(workoutId: String) = database.workoutDao().getWorkoutDetails(workoutId)
    suspend fun workouts() = database.workoutDao().getAllWorkouts()
    override suspend fun finishedWorkoutSummaries() = database.workoutDao().getFinishedWorkoutSummaries()

    override suspend fun createRoutineDraft(): RoutineEntity {
        val routine = RoutineEntity(
            id = UUID.randomUUID().toString(),
            name = "",
            isDraft = true,
        )
        database.routineDao().insertRoutine(routine)
        return routine
    }

    override suspend fun routineDetails(routineId: String) = database.routineDao().getRoutineDetails(routineId)
    suspend fun savedRoutines() = database.routineDao().getSavedRoutines()
    override suspend fun savedRoutineDetails() = database.routineDao().getSavedRoutineDetails()

    override suspend fun saveRoutine(routineId: String, name: String) =
        database.routineDao().saveRoutine(routineId, name)

    override suspend fun updateRoutineName(routineId: String, name: String) =
        database.routineDao().updateRoutineName(routineId, name)

    override suspend fun deleteRoutine(routineId: String) = database.routineDao().deleteRoutine(routineId)

    /** Saves a finished workout's exercise plan as a reusable routine. */
    override suspend fun saveWorkoutAsRoutine(workoutId: String, routineName: String): RoutineEntity? {
        val workout = database.workoutDao().getWorkoutDetails(workoutId) ?: return null
        if (workout.workout.status != "finished") return null

        val routine = RoutineEntity(
            id = UUID.randomUUID().toString(),
            name = routineName,
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
        val sets = workout.exercises.flatMapIndexed { exerciseIndex, workoutExercise ->
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
        database.routineDao().insertRoutineWithExercisesAndSets(routine, exercises, sets)
        return routine
    }

    /** Creates an active workout from a saved routine's exercises, sets, and rest durations. */
    override suspend fun startWorkoutFromRoutine(
        routineId: String,
        startedAtMillis: Long,
    ): WorkoutEntity? {
        val routine = database.routineDao().getRoutineDetails(routineId) ?: return null
        if (routine.routine.isDraft) return null

        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = routine.routine.name,
            startedAtMillis = startedAtMillis,
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
        val workoutSets = routine.exercises.flatMapIndexed { exerciseIndex, routineExercise ->
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
        database.workoutDao().insertWorkoutWithExercisesAndSets(workout, workoutExercises, workoutSets)
        return workout
    }

    override suspend fun addExercisesToRoutine(routineId: String, exerciseIds: Collection<String>) {
        if (exerciseIds.isEmpty()) return

        val routineDao = database.routineDao()
        val firstPosition = routineDao.getRoutineExercises(routineId).size
        val exercises = exerciseIds.mapIndexed { index, exerciseId ->
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
    }

    override suspend fun addSetToRoutineExercise(
        routineExerciseId: String,
        position: Int,
        repetitions: Int?,
        weightKg: Double?,
    ) = database.routineDao().insertRoutineSets(
        listOf(
            RoutineSetEntity(
                id = UUID.randomUUID().toString(),
                routineExerciseId = routineExerciseId,
                position = position,
                repetitions = repetitions,
                weightKg = weightKg,
            ),
        ),
    )

    override suspend fun updateRoutineSet(set: RoutineSetEntity) = database.routineDao().updateRoutineSet(set)

    override suspend fun updateRoutineExerciseRestDuration(routineExerciseId: String, seconds: Int) =
        database.routineDao().updateRestDuration(routineExerciseId, seconds)

    override suspend fun deleteRoutineExercise(routineExerciseId: String, routineId: String) =
        database.routineDao().deleteRoutineExerciseAndReorder(routineExerciseId, routineId)

    override suspend fun deleteRoutineSet(set: RoutineSetEntity) =
        database.routineDao().deleteRoutineSetAndReorder(set)

    /**
     * Creates an active copy of a saved workout. Exercise configuration and set inputs are
     * preserved, while every copied set starts unfinished with no recorded rest time.
     */
    override suspend fun performWorkoutAgain(workoutId: String, startedAtMillis: Long): WorkoutEntity? {
        val workoutDao = database.workoutDao()
        val source = workoutDao.getWorkoutDetails(workoutId) ?: return null
        if (source.workout.status != "finished") return null
        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            name = source.workout.name,
            startedAtMillis = startedAtMillis,
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
        val workoutSets = source.exercises.flatMapIndexed { exerciseIndex, sourceExercise ->
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
        workoutDao.insertWorkoutWithExercisesAndSets(workout, workoutExercises, workoutSets)
        return workout
    }

    suspend fun addExerciseToWorkout(
        workoutId: String,
        exerciseId: String,
        position: Int,
    ): WorkoutExerciseEntity {
        val workoutExercise = WorkoutExerciseEntity(
            id = UUID.randomUUID().toString(),
            workoutId = workoutId,
            exerciseId = exerciseId,
            position = position,
            restDurationSeconds = DEFAULT_REST_DURATION_SECONDS,
        )
        database.workoutDao().insertWorkoutExercise(workoutExercise)
        return workoutExercise
    }

    override suspend fun addSetToWorkoutExercise(
        workoutExerciseId: String,
        position: Int,
        repetitions: Int?,
        weightKg: Double?,
    ): WorkoutSetEntity {
        val workoutSet = WorkoutSetEntity(
            id = UUID.randomUUID().toString(),
            workoutExerciseId = workoutExerciseId,
            position = position,
            repetitions = repetitions,
            weightKg = weightKg,
        )
        database.workoutDao().insertWorkoutSet(workoutSet)
        return workoutSet
    }

    override suspend fun addExercisesToWorkout(workoutId: String, exerciseIds: Collection<String>) {
        if (exerciseIds.isEmpty()) return

        val workoutDao = database.workoutDao()
        val firstPosition = workoutDao.getWorkoutExercises(workoutId).size
        val previousRestDurations = exerciseIds.associateWith {
            workoutDao.previousRestDurationForExercise(it) ?: DEFAULT_REST_DURATION_SECONDS
        }
        val workoutExercises = exerciseIds.mapIndexed { index, exerciseId ->
            WorkoutExerciseEntity(
                id = UUID.randomUUID().toString(),
                workoutId = workoutId,
                exerciseId = exerciseId,
                position = firstPosition + index,
                restDurationSeconds = previousRestDurations.getValue(exerciseId),
            )
        }
        val workoutSets = workoutExercises.flatMap { workoutExercise ->
            val previousSets = workoutDao.previousCompletedSetsForExercise(
                workoutExercise.exerciseId,
            )
            if (previousSets.isEmpty()) {
                listOf(
                    WorkoutSetEntity(
                        id = UUID.randomUUID().toString(),
                        workoutExerciseId = workoutExercise.id,
                        position = 0,
                    ),
                )
            } else {
                previousSets.mapIndexed { position, previousSet ->
                    WorkoutSetEntity(
                        id = UUID.randomUUID().toString(),
                        workoutExerciseId = workoutExercise.id,
                        position = position,
                        repetitions = previousSet.repetitions,
                        weightKg = previousSet.weightKg,
                    )
                }
            }
        }
        workoutDao.insertExercisesWithInitialSets(workoutExercises, workoutSets)
    }

    override suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity) =
        database.workoutDao().updateWorkoutSet(workoutSet)

    override suspend fun updateWorkoutSetRestSeconds(workoutSetId: String, elapsedSeconds: Int) =
        database.workoutDao().updateWorkoutSetRestSeconds(workoutSetId, elapsedSeconds)

    override suspend fun updateWorkoutName(workoutId: String, name: String) =
        database.workoutDao().updateWorkoutName(workoutId, name)

    override suspend fun updateWorkoutExerciseNotes(workoutExerciseId: String, notes: String) =
        database.workoutDao().updateWorkoutExerciseNotes(workoutExerciseId, notes)

    override suspend fun updateWorkoutExerciseRestDuration(
        workoutExerciseId: String,
        restDurationSeconds: Int,
    ) = database.workoutDao().updateWorkoutExerciseRestDuration(
        workoutExerciseId,
        restDurationSeconds,
    )

    /** Replaces only the catalogue exercise, keeping this workout entry and its sets intact. */
    override suspend fun replaceWorkoutExercise(workoutExerciseId: String, exerciseId: String) =
        database.workoutDao().replaceWorkoutExercise(workoutExerciseId, exerciseId)

    /** Discarding a workout removes it and its cascaded exercises and sets. */
    override suspend fun discardWorkout(workoutId: String) = database.workoutDao().deleteWorkout(workoutId)

    /**
     * Finishes a workout only if it has a completed set, discarding unfinished sets and any
     * exercises left empty by that cleanup. Returns whether the workout was finished.
     */
    override suspend fun finishWorkout(
        workoutId: String,
        finishedAtMillis: Long,
    ): Boolean = database.workoutDao().finishWorkoutAndDiscardUnfinishedSets(
        workoutId,
        finishedAtMillis,
    )

    override suspend fun hasCompletedSets(workoutId: String): Boolean =
        database.workoutDao().completedSetCount(workoutId) > 0

    override suspend fun deleteWorkout(workoutId: String) = database.workoutDao().deleteWorkout(workoutId)

    override suspend fun deleteExerciseFromWorkout(workoutExerciseId: String, workoutId: String) =
        database.workoutDao().deleteWorkoutExerciseAndReorder(workoutExerciseId, workoutId)

    override suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity) =
        database.workoutDao().deleteWorkoutSetAndReorder(workoutSet)

}
