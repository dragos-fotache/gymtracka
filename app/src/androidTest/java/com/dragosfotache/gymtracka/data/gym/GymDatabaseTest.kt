package com.dragosfotache.gymtracka.data.gym

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

abstract class GymDatabaseTest {
    protected lateinit var database: GymDatabase
    internal lateinit var repository: GymRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        repository = GymRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    protected suspend fun insertExercises(vararg exercises: ExerciseEntity) {
        exercises.forEach { database.exerciseDao().insertExercise(it) }
    }

    protected fun insertMuscleGroups(vararg muscleGroups: MuscleGroupEntity) {
        muscleGroups.forEach { muscleGroup ->
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO muscle_groups (id, slug, name) VALUES (?, ?, ?)",
                arrayOf(muscleGroup.id, muscleGroup.slug, muscleGroup.name),
            )
        }
    }

    protected fun insertEquipment(vararg equipment: EquipmentEntity) {
        equipment.forEach { item ->
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO equipment (id, name, type, usage_type) VALUES (?, ?, ?, ?)",
                arrayOf(item.id, item.name, item.type, item.usageType),
            )
        }
    }

    protected suspend fun insertWorkoutAggregate(
        workout: WorkoutEntity,
        exercises: List<WorkoutExerciseEntity> = emptyList(),
        sets: List<WorkoutSetEntity> = emptyList(),
    ) {
        database.workoutDao().insertWorkoutWithExercisesAndSets(workout, exercises, sets)
    }

    protected suspend fun insertRoutineAggregate(
        routine: RoutineEntity,
        exercises: List<RoutineExerciseEntity> = emptyList(),
        sets: List<RoutineSetEntity> = emptyList(),
    ) {
        database.routineDao().insertRoutineWithExercisesAndSets(routine, exercises, sets)
    }
}
