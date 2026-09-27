package com.dragosfotache.gymtracka.data.gym

internal fun muscleGroupFixture(
    id: String = "muscle-1",
    slug: String = id,
    name: String = "Chest",
) = MuscleGroupEntity(id, slug, name)

internal fun equipmentFixture(
    id: String = "equipment-1",
    name: String = "Barbell",
    type: String = "weight",
    usageType: String? = null,
) = EquipmentEntity(id, name, type, usageType)

internal fun exerciseFixture(
    id: String = "exercise-1",
    name: String = "Bench Press",
    isUserCreated: Boolean = false,
) = ExerciseEntity(
    id = id,
    name = name,
    type = "reps",
    difficultyLevel = null,
    forceType = null,
    mechanics = null,
    category = "strength",
    instructions = listOf("Lower with control", "Press to lockout"),
    isUserCreated = isUserCreated,
)

internal fun exerciseMuscleGroupFixture(
    exerciseId: String = "exercise-1",
    muscleGroupId: String = "muscle-1",
    type: String = "primary",
) = ExerciseMuscleGroupEntity(exerciseId, muscleGroupId, type)

internal fun exerciseEquipmentFixture(
    exerciseId: String = "exercise-1",
    equipmentId: String = "equipment-1",
    numberOfItems: Int? = null,
) = ExerciseEquipmentEntity(exerciseId, equipmentId, numberOfItems)

internal fun workoutFixture(
    id: String = "workout-1",
    name: String = "Test Workout",
    startedAtMillis: Long = 1_000L,
    finishedAtMillis: Long? = null,
    status: String = "active",
) = WorkoutEntity(id, name, startedAtMillis, finishedAtMillis, status)

internal fun workoutExerciseFixture(
    id: String = "workout-exercise-1",
    workoutId: String = "workout-1",
    exerciseId: String = "exercise-1",
    position: Int = 0,
    notes: String = "",
    restDurationSeconds: Int = 30,
) = WorkoutExerciseEntity(
    id,
    workoutId,
    exerciseId,
    position,
    notes,
    restDurationSeconds,
)

internal fun workoutSetFixture(
    id: String = "workout-set-1",
    workoutExerciseId: String = "workout-exercise-1",
    position: Int = 0,
    repetitions: Int? = 8,
    weightKg: Double? = 50.0,
    isCompleted: Boolean = false,
    restSeconds: Int? = null,
) = WorkoutSetEntity(
    id,
    workoutExerciseId,
    position,
    repetitions,
    weightKg,
    isCompleted,
    restSeconds,
)

internal fun routineFixture(
    id: String = "routine-1",
    name: String = "Test Routine",
    isDraft: Boolean = false,
) = RoutineEntity(id, name, isDraft)

internal fun routineExerciseFixture(
    id: String = "routine-exercise-1",
    routineId: String = "routine-1",
    exerciseId: String = "exercise-1",
    position: Int = 0,
    restDurationSeconds: Int = 30,
) = RoutineExerciseEntity(id, routineId, exerciseId, position, restDurationSeconds)

internal fun routineSetFixture(
    id: String = "routine-set-1",
    routineExerciseId: String = "routine-exercise-1",
    position: Int = 0,
    repetitions: Int? = 8,
    weightKg: Double? = 50.0,
) = RoutineSetEntity(id, routineExerciseId, position, repetitions, weightKg)
