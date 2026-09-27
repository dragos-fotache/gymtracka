package com.dragosfotache.gymtracka.data.gym

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomExercisePersistenceTest : GymDatabaseTest() {
    @Test
    fun createCustomExercise_persistsFieldsAndNormalizesMuscleRelationships() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = "chest", name = "Chest"),
            muscleGroupFixture(id = "triceps", name = "Triceps"),
        )
        insertEquipment(equipmentFixture(id = "barbell"))

        val id = repository.createCustomExercise(
            name = "Custom Press",
            primaryMuscleGroupId = "chest",
            secondaryMuscleGroupIds = linkedSetOf("chest", "triceps"),
            equipmentId = "barbell",
            category = "strength",
            instructions = "  Press with control  ",
        )

        val details = repository.exerciseDetails(id)!!
        assertEquals("Custom Press", details.exercise.name)
        assertEquals("reps", details.exercise.type)
        assertEquals("strength", details.exercise.category)
        assertEquals(listOf("Press with control"), details.exercise.instructions)
        assertTrue(details.exercise.isUserCreated)
        assertEquals(
            listOf("chest" to "primary", "triceps" to "secondary"),
            details.muscleGroups.map { it.id to it.relationshipType },
        )
        assertEquals(listOf("barbell"), details.equipment.map { it.id })
    }

    @Test
    fun updateCustomExercise_atomicallyReplacesFieldsAndRelationships() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = "chest", name = "Chest"),
            muscleGroupFixture(id = "back", name = "Back"),
            muscleGroupFixture(id = "biceps", name = "Biceps"),
        )
        insertEquipment(
            equipmentFixture(id = "barbell", name = "Barbell"),
            equipmentFixture(id = "dumbbell", name = "Dumbbell"),
        )
        val exercise = exerciseFixture(id = "custom", name = "Old", isUserCreated = true)
        insertExercises(exercise)
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(exerciseMuscleGroupFixture(exercise.id, "chest", "primary")),
        )
        database.exerciseDao().insertExerciseEquipment(
            listOf(exerciseEquipmentFixture(exercise.id, "barbell")),
        )

        repository.updateCustomExercise(
            exerciseId = exercise.id,
            name = "New Row",
            primaryMuscleGroupId = "back",
            secondaryMuscleGroupIds = linkedSetOf("back", "biceps"),
            equipmentId = "dumbbell",
            category = "hypertrophy",
            instructions = "Pull",
        )

        val details = repository.exerciseDetails(exercise.id)!!
        assertEquals("New Row", details.exercise.name)
        assertEquals("hypertrophy", details.exercise.category)
        assertEquals(listOf("Pull"), details.exercise.instructions)
        assertEquals(
            listOf("back" to "primary", "biceps" to "secondary"),
            details.muscleGroups.map { it.id to it.relationshipType },
        )
        assertEquals(listOf("dumbbell"), details.equipment.map { it.id })
    }

    @Test
    fun updateCustomExercise_rollsBackFieldsAndRelationships_whenInsertionFails() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = "old-muscle", name = "Old"),
            muscleGroupFixture(id = "new-muscle", name = "New"),
        )
        val exercise = exerciseFixture(id = "custom", name = "Original", isUserCreated = true)
        val oldRelation = exerciseMuscleGroupFixture(exercise.id, "old-muscle", "primary")
        insertExercises(exercise)
        database.exerciseDao().insertExerciseMuscleGroups(listOf(oldRelation))
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_new_custom_relationship
                BEFORE INSERT ON exercise_muscle_groups
                BEGIN SELECT RAISE(ABORT, 'forced relationship failure'); END""",
        )

        var failure: Throwable? = null
        try {
            repository.updateCustomExercise(
                exerciseId = exercise.id,
                name = "Should Roll Back",
                primaryMuscleGroupId = "new-muscle",
                secondaryMuscleGroupIds = emptySet(),
                equipmentId = null,
                category = "changed",
                instructions = "changed",
            )
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        val details = repository.exerciseDetails(exercise.id)!!
        assertEquals(exercise, details.exercise)
        assertEquals(listOf("old-muscle" to "primary"), details.muscleGroups.map { it.id to it.relationshipType })
    }

    @Test
    fun bundledExercises_cannotBeUpdatedOrDeleted() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        val bundled = exerciseFixture(isUserCreated = false)
        val relation = exerciseMuscleGroupFixture()
        insertExercises(bundled)
        database.exerciseDao().insertExerciseMuscleGroups(listOf(relation))

        repository.updateCustomExercise(
            exerciseId = bundled.id,
            name = "Changed",
            primaryMuscleGroupId = null,
            secondaryMuscleGroupIds = emptySet(),
            equipmentId = null,
            category = null,
            instructions = "",
        )
        repository.deleteCustomExercise(bundled.id)

        assertEquals(bundled, repository.exercise(bundled.id))
        assertEquals(
            listOf("muscle-1" to "primary"),
            repository.exerciseDetails(bundled.id)?.muscleGroups?.map { it.id to it.relationshipType },
        )
    }

    @Test
    fun deleteCustomExercise_removesItsRelationshipsAndExercise() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        insertEquipment(equipmentFixture())
        val custom = exerciseFixture(id = "custom", isUserCreated = true)
        insertExercises(custom)
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(exerciseMuscleGroupFixture(exerciseId = custom.id)),
        )
        database.exerciseDao().insertExerciseEquipment(
            listOf(exerciseEquipmentFixture(exerciseId = custom.id)),
        )

        repository.deleteCustomExercise(custom.id)

        assertNull(repository.exercise(custom.id))
        assertTrue(database.exerciseDao().getMuscleGroups(custom.id).isEmpty())
        assertTrue(database.exerciseDao().getEquipment(custom.id).isEmpty())
    }

    @Test
    fun deleteCustomExercise_rollsBackRelationshipRemoval_whenParentDeletionFails() = runTest {
        insertMuscleGroups(muscleGroupFixture())
        insertEquipment(equipmentFixture())
        val custom = exerciseFixture(id = "custom", isUserCreated = true)
        insertExercises(custom)
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(exerciseMuscleGroupFixture(exerciseId = custom.id)),
        )
        database.exerciseDao().insertExerciseEquipment(
            listOf(exerciseEquipmentFixture(exerciseId = custom.id)),
        )
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_custom_exercise_delete
                BEFORE DELETE ON exercises
                BEGIN SELECT RAISE(ABORT, 'forced delete failure'); END""",
        )

        var failure: Throwable? = null
        try {
            repository.deleteCustomExercise(custom.id)
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull(failure)
        assertEquals(custom, repository.exercise(custom.id))
        assertEquals(1, database.exerciseDao().getMuscleGroups(custom.id).size)
        assertEquals(1, database.exerciseDao().getEquipment(custom.id).size)
    }

    @Test
    fun catalogueSummaries_includeCompleteRelationshipsForEveryExercise() = runTest {
        insertMuscleGroups(
            muscleGroupFixture(id = "chest", name = "Chest"),
            muscleGroupFixture(id = "triceps", name = "Triceps"),
        )
        insertEquipment(
            equipmentFixture(id = "barbell", name = "Barbell"),
            equipmentFixture(id = "bench", name = "Bench"),
        )
        val press = exerciseFixture(id = "press", name = "Press")
        val fly = exerciseFixture(id = "fly", name = "Fly")
        insertExercises(press, fly)
        database.exerciseDao().insertExerciseMuscleGroups(
            listOf(
                exerciseMuscleGroupFixture(press.id, "chest", "primary"),
                exerciseMuscleGroupFixture(press.id, "triceps", "secondary"),
                exerciseMuscleGroupFixture(fly.id, "chest", "primary"),
            ),
        )
        database.exerciseDao().insertExerciseEquipment(
            listOf(
                exerciseEquipmentFixture(press.id, "barbell"),
                exerciseEquipmentFixture(press.id, "bench"),
                exerciseEquipmentFixture(fly.id, "bench"),
            ),
        )

        val summaries = repository.exerciseSummaries().associateBy { it.exercise.id }

        assertEquals(setOf("press", "fly"), summaries.keys)
        assertEquals(setOf("chest", "triceps"), summaries.getValue("press").muscleGroups.map { it.id }.toSet())
        assertEquals(setOf("barbell", "bench"), summaries.getValue("press").equipment.map { it.id }.toSet())
        assertEquals(listOf("chest"), summaries.getValue("fly").muscleGroups.map { it.id })
        assertEquals(listOf("bench"), summaries.getValue("fly").equipment.map { it.id })
        assertFalse(summaries.getValue("press").exercise.isUserCreated)
    }
}
