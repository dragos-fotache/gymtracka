package com.dragosfotache.gymtracka.testing

import com.dragosfotache.gymtracka.data.gym.ExerciseDetails
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseDetails
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseWithName
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseWithName
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity

internal fun workoutDetailsFixture(
    name: String = "Workout",
    restDurationSeconds: Int = 30,
    sets: List<WorkoutSetEntity> = listOf(
        WorkoutSetEntity("set", "entry", 0, 8, 50.0),
    ),
) = WorkoutDetails(
    workout = WorkoutEntity("workout", name, 1_000L),
    exercises = listOf(
        WorkoutExerciseDetails(
            workoutExercise = WorkoutExerciseWithName(
                id = "entry",
                workoutId = "workout",
                exerciseId = "exercise",
                position = 0,
                notes = "",
                restDurationSeconds = restDurationSeconds,
                exerciseName = "Exercise",
            ),
            sets = sets,
        ),
    ),
)

internal fun routineDetailsFixture(
    name: String = "Routine",
    sets: List<RoutineSetEntity> = listOf(
        RoutineSetEntity("routine-set", "routine-entry", 0, 8, 50.0),
    ),
) = RoutineDetails(
    routine = RoutineEntity("routine", name),
    exercises = listOf(
        RoutineExerciseDetails(
            routineExercise = RoutineExerciseWithName(
                id = "routine-entry",
                routineId = "routine",
                exerciseId = "exercise",
                position = 0,
                restDurationSeconds = 30,
                exerciseName = "Exercise",
            ),
            sets = sets,
        ),
    ),
)

internal fun customExerciseDetailsFixture(name: String = "Loaded") = ExerciseDetails(
    exercise = ExerciseEntity(
        id = "custom",
        name = name,
        type = "reps",
        difficultyLevel = null,
        forceType = null,
        mechanics = null,
        category = null,
        instructions = null,
        isUserCreated = true,
    ),
    muscleGroups = emptyList(),
    equipment = emptyList(),
)
