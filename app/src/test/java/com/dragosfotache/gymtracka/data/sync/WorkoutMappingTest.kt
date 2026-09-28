package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.WorkoutDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseDetails
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseWithName
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.mapping.toRoomAggregate
import com.dragosfotache.gymtracka.data.sync.mapping.toWorkoutInput
import com.dragosfotache.gymtracka.data.sync.mapping.toWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Room mapping behavior: ordering, identity preservation, and structural rejection. */
class WorkoutMappingTest {

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun details(
        workoutId: String = uuid(),
        status: String = "active",
        finishedAtMillis: Long? = null,
        exerciseOrder: List<Int> = listOf(0, 1),
        setOrder: List<Int> = listOf(0, 1),
    ): WorkoutDetails {
        val firstEntry = uuid()
        val secondEntry = uuid()
        val catalogueId = uuid()
        val entries = listOf(
            WorkoutExerciseDetails(
                WorkoutExerciseWithName(firstEntry, workoutId, catalogueId, 0, "notes-a", 60, "Alpha"),
                setOrder.map { position ->
                    WorkoutSetEntity(uuid(), firstEntry, position, 5, 50.0, position == 0, null)
                },
            ),
            WorkoutExerciseDetails(
                WorkoutExerciseWithName(secondEntry, workoutId, catalogueId, 1, "", 30, "Beta"),
                listOf(WorkoutSetEntity(uuid(), secondEntry, 0, null, null, false, null)),
            ),
        )
        return WorkoutDetails(
            WorkoutEntity(workoutId, "Name", 1_000L, finishedAtMillis, status),
            exerciseOrder.map { entries[it] },
        )
    }

    @Test
    fun roomMapping_sortsShuffledChildrenAndPreservesIds() {
        val rootId = uuid()
        val source = details(rootId, exerciseOrder = listOf(1, 0), setOrder = listOf(1, 0))
        val input = source.toWorkoutInput()

        assertEquals(rootId, input.id)
        assertEquals(listOf(0, 1), input.exercises.map { it.position })
        assertEquals(listOf("notes-a", ""), input.exercises.map { it.notes })
        assertEquals(listOf(0, 1), input.exercises[0].sets.map { it.position })
        assertEquals(listOf(true, false), input.exercises[0].sets.map { it.isCompleted })

        val record = source.toWorkoutRecord(revision = 3L)
        assertEquals(3L, record.revision)
        assertEquals(listOf("Alpha", "Beta"), record.exercises.map { it.exerciseName })

        val aggregate = record.toRoomAggregate("account-x", 9L)
        assertEquals(rootId, aggregate.workout.id)
        assertEquals("account-x", aggregate.workout.accountId)
        assertEquals(3L, aggregate.workout.serverRevision)
        assertEquals(9L, aggregate.workout.localGeneration)
        assertEquals(aggregate.exercises.map { it.id }, input.exercises.map { it.id })
        aggregate.exercises.forEach { assertEquals(rootId, it.workoutId) }
        aggregate.sets.forEach { set ->
            assertEquals(1, aggregate.exercises.count { it.id == set.workoutExerciseId })
        }
    }

    @Test
    fun roomMapping_rejectsForeignChildren() {
        val rootId = uuid()
        val foreign = details(rootId).let { source ->
            val tampered = source.exercises[0].workoutExercise.copy(workoutId = uuid())
            source.copy(exercises = listOf(source.exercises[0].copy(workoutExercise = tampered)) + source.exercises[1])
        }
        assertFails { foreign.toWorkoutInput() }
        assertFails { foreign.toWorkoutRecord(1L) }

        val foreignSet = details(rootId).let { source ->
            val tampered = source.exercises[0].sets[0].copy(workoutExerciseId = uuid())
            val entry = source.exercises[0].copy(sets = listOf(tampered) + source.exercises[0].sets[1])
            source.copy(exercises = listOf(entry) + source.exercises[1])
        }
        assertFails { foreignSet.toWorkoutInput() }
    }

    @Test
    fun roomMapping_rejectsPositionGapsDuplicatesAndDuplicateIds() {
        val rootId = uuid()
        val base = details(rootId)

        val gapped = base.copy(
            exercises = base.exercises.map { it.copy(workoutExercise = it.workoutExercise.copy(position = it.workoutExercise.position + 1)) },
        )
        assertFails { gapped.toWorkoutInput() }

        val duplicated = base.copy(
            exercises = listOf(base.exercises[0], base.exercises[0]),
        )
        assertFails { duplicated.toWorkoutInput() }

        val duplicateSets = base.copy(
            exercises = listOf(
                base.exercises[0].copy(
                    sets = listOf(base.exercises[0].sets[0], base.exercises[0].sets[0]),
                ),
                base.exercises[1],
            ),
        )
        assertFails { duplicateSets.toWorkoutInput() }
    }

    @Test
    fun finishedDetails_cannotMapToUploadInput() {
        assertFails { details(status = "finished", finishedAtMillis = 2_000L).toWorkoutInput() }
        assertFails { details(status = "finished", finishedAtMillis = null).toWorkoutRecord(1L) }
    }

    @Test
    fun recordMapping_requiresPositiveRevision() {
        assertFails { details().toWorkoutRecord(0L) }
        val record: WorkoutRecordModel = details().toWorkoutRecord(2L)
        assertEquals(2L, record.toRoomAggregate("account-x", 0L).workout.serverRevision)
    }

    @Test
    fun roomAggregate_guardsExplicitMappingMetadata() {
        val record: WorkoutRecordModel = details().toWorkoutRecord(2L)
        assertFails { record.toRoomAggregate("", 0L) }
        assertFails { record.toRoomAggregate("   ", 0L) }
        assertFails { record.toRoomAggregate("account-x", -1L) }
        assertEquals("account-x", record.toRoomAggregate("account-x", 0L).workout.accountId)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: SyncModelException) {
            return
        }
        fail("Expected SyncModelException")
    }
}
