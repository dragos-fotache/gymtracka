package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.mapping.toRoomAggregate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture compatibility: the supplied finished-workout aggregate decodes exactly. */
class WorkoutFixtureTest {

    private fun loadFixture(): String =
        (
            javaClass.classLoader?.getResourceAsStream("fixtures/finished-workout.json")
                ?: error("Missing test fixture fixtures/finished-workout.json")
            ).bufferedReader().readText()

    @Test
    fun finishedFixture_decodesWithExactIdsValuesAndTimestamps() {
        val record = decodeWorkoutRecord(loadFixture())

        assertEquals("e9cd4a3c-4cce-4b7d-8b7b-8763956a2f15", record.id)
        assertEquals("Afternoon Workout", record.name)
        assertEquals("finished", record.status)
        assertEquals(Instant.parse("2026-09-24T14:00:00Z").toEpochMilli(), record.startedAtMillis)
        assertEquals(Instant.parse("2026-09-24T15:02:12Z").toEpochMilli(), record.finishedAtMillis)
        assertEquals(12L, record.revision)

        assertEquals(1, record.exercises.size)
        val exercise = record.exercises.single()
        assertEquals("df037724-faf9-4b2e-993f-f37993726ee1", exercise.id)
        assertEquals("a52af213-b0b8-4d7a-a43f-7758f838dc41", exercise.exerciseId)
        assertEquals("Barbell Squat", exercise.exerciseName)
        assertEquals(0, exercise.position)
        assertEquals("Controlled depth", exercise.notes)
        assertEquals(90, exercise.restDurationSeconds)

        assertEquals(1, exercise.sets.size)
        val set = exercise.sets.single()
        assertEquals("10dfcb31-4588-421e-9800-0d40e3c7b863", set.id)
        assertEquals(0, set.position)
        assertEquals(8, set.repetitions)
        assertEquals(100.0, set.weightKg)
        assertEquals(true, set.isCompleted)
        assertEquals(87, set.restSeconds)
    }

    @Test
    fun finishedFixture_canonicalRoundTripIgnoresDerivedVolume() {
        val record = decodeWorkoutRecord(loadFixture())
        val reencoded = encodeWorkoutRecord(record)
        // Derived convenience value is ignored on decode and never re-emitted.
        assertTrue(!reencoded.has("totalVolumeKg"))
        assertEquals(record, decodeWorkoutRecord(reencoded))
    }

    @Test
    fun finishedFixture_flattensToRoomDomainFields() {
        val record = decodeWorkoutRecord(loadFixture())
        val aggregate = record.toRoomAggregate(accountId = "account-a", localGeneration = 5L)

        assertEquals(record.id, aggregate.workout.id)
        assertEquals("Afternoon Workout", aggregate.workout.name)
        assertEquals("finished", aggregate.workout.status)
        assertEquals("account-a", aggregate.workout.accountId)
        assertEquals(12L, aggregate.workout.serverRevision)
        assertEquals(5L, aggregate.workout.localGeneration)
        assertEquals("synced", aggregate.workout.syncState)
        assertNull(aggregate.workout.deletedAtMillis)
        assertEquals(record.finishedAtMillis, aggregate.workout.finishedAtMillis)

        assertEquals(1, aggregate.exercises.size)
        val exercise = aggregate.exercises.single()
        assertEquals("df037724-faf9-4b2e-993f-f37993726ee1", exercise.id)
        assertEquals(record.id, exercise.workoutId)
        assertEquals("a52af213-b0b8-4d7a-a43f-7758f838dc41", exercise.exerciseId)
        assertEquals(0, exercise.position)
        assertEquals("Controlled depth", exercise.notes)
        assertEquals(90, exercise.restDurationSeconds)

        assertEquals(1, aggregate.sets.size)
        val set = aggregate.sets.single()
        assertEquals(exercise.id, set.workoutExerciseId)
        assertEquals(8, set.repetitions)
        assertEquals(100.0, set.weightKg)
        assertEquals(true, set.isCompleted)
        assertEquals(87, set.restSeconds)
    }
}
