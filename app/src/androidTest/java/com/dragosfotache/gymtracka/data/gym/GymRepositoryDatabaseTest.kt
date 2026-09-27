package com.dragosfotache.gymtracka.data.gym

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GymRepositoryDatabaseTest : GymDatabaseTest() {
    @Test
    fun injectedDatabase_isEmptyAndPersistsOnlyTestData() = runTest {
        assertNull(repository.activeWorkout())

        val workout = workoutFixture()
        database.workoutDao().insertWorkout(workout)

        assertEquals(workout, repository.activeWorkout())
        assertEquals(listOf(workout), repository.workouts())
    }
}
