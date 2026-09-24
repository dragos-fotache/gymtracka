package com.dragosfotache.gymtracka

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.dragosfotache.gymtracka", appContext.packageName)
    }

    @Test
    fun opensPrepackagedGymDatabase() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val database = GymDatabase.getInstance(appContext)

        assertTrue(database.exerciseDao().getAll().isNotEmpty())
        assertTrue(database.muscleGroupDao().getAll().isNotEmpty())
    }
}
