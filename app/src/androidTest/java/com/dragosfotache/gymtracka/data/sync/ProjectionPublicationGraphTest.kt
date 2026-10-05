package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.gym.routineFixture
import com.dragosfotache.gymtracka.data.gym.workoutExerciseFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.gym.workoutSetFixture
import com.dragosfotache.gymtracka.data.sync.store.CanonicalConflictException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.ProjectionCollisionException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionGraphException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R05c independent acceptance: whole-graph validation, physical collisions,
 * generation preservation, epoch signal, rollback and file-backed persistence.
 * Real Room, raw SQLite oracles, handwritten expectations from R05c-task.md.
 */
class ProjectionPublicationGraphTest : GymDatabaseTest() {

    private suspend fun seedLookups() {
        insertMuscleGroups(
            muscleGroupFixture(id = R05C_MUSCLE_PRIMARY, name = "Chest"),
            muscleGroupFixture(id = R05C_MUSCLE_SECONDARY, name = "Shoulders"),
        )
        insertEquipment(equipmentFixture(id = R05C_EQUIPMENT, name = "Dumbbell"))
        insertExercises(exerciseFixture(id = R05C_BUNDLED, name = "Bench Press"))
    }

    private fun customA(revision: Long = 1L) = r05bCustomRecord(
        id = R05B_CUSTOM_A,
        name = "Fly",
        revision = revision,
        category = "strength",
        muscleGroups = r05bJsonArray(r05bMuscleRef(R05C_MUSCLE_PRIMARY, "Chest", "primary")),
        equipment = r05bJsonArray(r05bEquipmentRef(R05C_EQUIPMENT, "Dumbbell", 2)),
    )

    private fun assertSafeMessage(message: String?, vararg secrets: String) {
        val text = message ?: ""
        secrets.forEach { assertTrue("message leaks domain value", !text.contains(it)) }
    }

    private suspend fun assertPublishThrowsGraphUnchanged(): ProjectionGraphException {
        val before = database.r05cDumpAll()
        val lease = R05cTestLease()
        try {
            database.r05cStore(barrier = r05cGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionGraphException")
            throw AssertionError("unreachable")
        } catch (e: ProjectionGraphException) {
            assertSafeMessage(e.message, R05B_ACCOUNT, R05B_WORKOUT_A, R05B_CUSTOM_A)
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.r05cDumpAll())
            return e
        }
    }

    private suspend fun assertPublishThrowsCollisionUnchanged(): ProjectionCollisionException {
        val before = database.r05cDumpAll()
        val lease = R05cTestLease()
        try {
            database.r05cStore(barrier = r05cGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionCollisionException")
            throw AssertionError("unreachable")
        } catch (e: ProjectionCollisionException) {
            assertSafeMessage(e.message, R05B_ACCOUNT, R05B_WORKOUT_A, R05B_CUSTOM_A)
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.r05cDumpAll())
            return e
        }
    }

    // Whole-graph dependency validation.

    @Test
    fun unknownCustomDependencyFails() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(exerciseId = "99999999-9999-4999-8999-999999999999"),
                        ),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
    }

    @Test
    fun deletedCustomDependencyFails() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_DELETE, 2L, null),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
        val root = database.syncCanonicalDao().getRoot(R05B_ACCOUNT, R05B_TYPE_CUSTOM, R05B_CUSTOM_A)!!
        assertEquals("delete", root.canonicalState)
    }

    @Test
    fun localOnlyCustomDependencyFails() = runTest {
        seedLookups()
        val localId = "aaaaaaaa-0000-4000-8000-00000000c106"
        insertExercises(exerciseFixture(id = localId, name = "Local", isUserCreated = true))
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = localId)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
    }

    @Test
    fun foreignCustomDependencyFails() = runTest {
        seedLookups()
        val foreignCustom = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb01bb"
        insertExercises(
            exerciseFixture(id = foreignCustom, name = "Foreign", isUserCreated = true)
                .copy(accountId = R05B_OTHER_ACCOUNT),
        )
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = foreignCustom)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
        assertEquals(
            R05B_OTHER_ACCOUNT,
            database.r05cQuery(
                "SELECT account_id FROM exercises WHERE id = ?",
                arrayOf(foreignCustom),
            ).single()["account_id"],
        )
    }

    @Test
    fun missingMuscleThenEquipmentFailBeforeSuccess() = runTest {
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
        insertMuscleGroups(muscleGroupFixture(id = R05C_MUSCLE_PRIMARY, name = "Chest"))
        assertPublishThrowsGraphUnchanged()
        insertEquipment(equipmentFixture(id = R05C_EQUIPMENT, name = "Dumbbell"))
        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        val muscles = database.r05cQuery(
            "SELECT muscle_group_id FROM exercise_muscle_groups WHERE exercise_id = ?",
            arrayOf(R05B_CUSTOM_A),
        )
        assertEquals(listOf(R05C_MUSCLE_PRIMARY), muscles.map { it["muscle_group_id"] })
    }

    @Test
    fun multipleActiveWorkoutsFail() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        name = "First",
                        status = "active",
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_B,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        id = R05B_WORKOUT_B,
                        name = "Second",
                        status = "active",
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                id = R05C_ENTRY_X1,
                                exerciseId = R05C_BUNDLED,
                                sets = r05bJsonArray(r05bWorkoutSet(id = R05C_SET_X1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
    }

    @Test
    fun completeBatchActiveReplacementSucceeds() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        name = "Old",
                        status = "active",
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        database.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_2,
                cursor = R05B_CURSOR_2,
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_WORKOUT, R05B_WORKOUT_A, R05B_OP_DELETE, 2L, null),
                    r05bChange(
                        R05B_TYPE_WORKOUT,
                        R05B_WORKOUT_B,
                        R05B_OP_UPSERT,
                        1L,
                        r05bWorkoutRecord(
                            id = R05B_WORKOUT_B,
                            name = "New",
                            status = "active",
                            revision = 1L,
                            exercises = r05bJsonArray(
                                r05bWorkoutEntry(
                                    id = R05C_ENTRY_X1,
                                    exerciseId = R05C_BUNDLED,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            1L,
        )
        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(2L, result.cacheEpoch)
        assertTrue(
            database.r05cQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(R05B_WORKOUT_A)).isEmpty(),
        )
        val active = database.r05cQuery("SELECT name FROM workouts WHERE status = 'active'")
        assertEquals(listOf("New"), active.map { it["name"] })
    }

    // Physical collisions.

    @Test
    fun localRootPkCollisionFails() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(workoutFixture(id = R05B_WORKOUT_A, name = "Local"))
        assertPublishThrowsCollisionUnchanged()
    }

    @Test
    fun foreignRootPkCollisionFails() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(workoutFixture(id = R05B_WORKOUT_A).copy(accountId = R05B_OTHER_ACCOUNT))
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            R05B_OTHER_ACCOUNT,
            database.r05cQuery(
                "SELECT account_id FROM workouts WHERE id = ?",
                arrayOf(R05B_WORKOUT_A),
            ).single()["account_id"],
        )
    }

    @Test
    fun bundledCustomClaimCollisionFails() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(
                    R05B_TYPE_CUSTOM,
                    R05C_BUNDLED,
                    R05B_OP_UPSERT,
                    1L,
                    r05bCustomRecord(id = R05C_BUNDLED, name = "Claimed", revision = 1L),
                ),
            ),
        )
        assertPublishThrowsCollisionUnchanged()
        val bundled = database.r05cQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(R05C_BUNDLED)).single()
        assertEquals("Bench Press", bundled["name"])
        assertEquals(0L, bundled["is_user_created"])
    }

    @Test
    fun entryAndSetPkAndRoleCollisionsFail() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                id = R05B_ENTRY_A1,
                                exerciseId = R05B_CUSTOM_A,
                                sets = r05bJsonArray(r05bWorkoutSet(id = R05B_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture(id = R05C_WORKOUT_B),
            listOf(workoutExerciseFixture(id = R05B_ENTRY_A1, workoutId = R05C_WORKOUT_B, exerciseId = R05C_BUNDLED)),
            listOf(workoutSetFixture(id = R05B_SET_A1, workoutExerciseId = R05B_ENTRY_A1)),
        )
        assertPublishThrowsCollisionUnchanged()

        database.openHelper.writableDatabase.execSQL("DELETE FROM workout_sets")
        database.openHelper.writableDatabase.execSQL("DELETE FROM workout_exercises")
        database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
        insertWorkoutAggregate(
            workoutFixture(id = R05C_WORKOUT_B),
            listOf(workoutExerciseFixture(id = R05C_ENTRY_X2, workoutId = R05C_WORKOUT_B, exerciseId = R05C_BUNDLED)),
            listOf(workoutSetFixture(id = R05B_ENTRY_A1, workoutExerciseId = R05C_ENTRY_X2)),
        )
        assertPublishThrowsCollisionUnchanged()
    }

    @Test
    fun sameAccountWrongRootReuseFailsDespiteRemoval() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_B,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        id = R05B_WORKOUT_B,
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                id = R05C_ENTRY_X1,
                                exerciseId = R05B_CUSTOM_A,
                                sets = r05bJsonArray(r05bWorkoutSet(id = R05B_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture(id = R05C_WORKOUT_B).copy(accountId = R05B_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = R05B_ENTRY_A1,
                    workoutId = R05C_WORKOUT_B,
                    exerciseId = R05C_BUNDLED,
                ),
            ),
            listOf(workoutSetFixture(id = R05B_SET_A1, workoutExerciseId = R05B_ENTRY_A1)),
        )
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            R05C_WORKOUT_B,
            database.r05cQuery(
                "SELECT workout_id FROM workout_exercises WHERE id = ?",
                arrayOf(R05B_ENTRY_A1),
            ).single()["workout_id"],
        )
    }

    @Test
    fun validSameBindingAndWorkoutRoutineIdOverlapSucceed() = runTest {
        seedLookups()
        val sharedId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaa102"
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    sharedId,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        id = sharedId,
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                id = R05B_ENTRY_A1,
                                exerciseId = R05B_CUSTOM_A,
                                sets = r05bJsonArray(r05bWorkoutSet(id = R05B_SET_A1)),
                            ),
                        ),
                    ),
                ),
                r05bChange(
                    R05B_TYPE_ROUTINE,
                    sharedId,
                    R05B_OP_UPSERT,
                    1L,
                    r05bRoutineRecord(
                        id = sharedId,
                        name = "Shared",
                        isDraft = false,
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bRoutineEntry(
                                id = R05B_ENTRY_A2,
                                exerciseId = R05B_CUSTOM_A,
                                sets = r05bJsonArray(r05bRoutineSet(id = R05B_SET_A2)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertExercises(
            exerciseFixture(id = R05B_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = R05B_ACCOUNT),
        )
        insertWorkoutAggregate(
            workoutFixture(id = sharedId).copy(accountId = R05B_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = R05B_ENTRY_A1,
                    workoutId = sharedId,
                    exerciseId = R05B_CUSTOM_A,
                ),
            ),
            listOf(workoutSetFixture(id = R05B_SET_A1, workoutExerciseId = R05B_ENTRY_A1)),
        )
        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertEquals(
            sharedId,
            database.r05cQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(sharedId)).single()["id"],
        )
        assertEquals(
            "Shared",
            database.r05cQuery("SELECT name FROM routines WHERE id = ?", arrayOf(sharedId)).single()["name"],
        )
    }

    @Test
    fun crossScopeReferenceToRemovedCustomFailsWithoutTouchingOtherScope() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        insertWorkoutAggregate(
            workoutFixture(id = R05C_WORKOUT_B).copy(accountId = R05B_OTHER_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = R05C_ENTRY_X2,
                    workoutId = R05C_WORKOUT_B,
                    exerciseId = R05B_CUSTOM_A,
                ),
            ),
            listOf(workoutSetFixture(id = R05C_SET_X2, workoutExerciseId = R05C_ENTRY_X2)),
        )
        database.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_2,
                cursor = R05B_CURSOR_2,
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_DELETE, 2L, null),
                    r05bChange(
                        R05B_TYPE_WORKOUT,
                        R05B_WORKOUT_A,
                        R05B_OP_UPSERT,
                        2L,
                        r05bWorkoutRecord(
                            revision = 2L,
                            exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05C_BUNDLED)),
                        ),
                    ),
                ),
            ),
            1L,
        )
        val before = database.r05cDumpAll()
        val lease = R05cTestLease()
        try {
            database.r05cStore(barrier = r05cGrantBarrier(lease)).publish(1L)
            fail("Expected safe failure for surviving cross-scope reference")
        } catch (e: ProjectionGraphException) {
            assertSafeMessage(e.message, R05B_ACCOUNT, R05B_CUSTOM_A)
        } catch (e: CanonicalStorageException) {
            assertSafeMessage(e.message, R05B_ACCOUNT, R05B_CUSTOM_A)
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.r05cDumpAll())
        assertEquals(
            R05B_CUSTOM_A,
            database.r05cQuery(
                "SELECT exercise_id FROM workout_exercises WHERE id = ?",
                arrayOf(R05C_ENTRY_X2),
            ).single()["exercise_id"],
        )
        assertEquals(
            "Fly",
            database.r05cQuery(
                "SELECT name FROM exercises WHERE id = ?",
                arrayOf(R05B_CUSTOM_A),
            ).single()["name"],
        )
    }

    // Generation preservation and publication signal.

    @Test
    fun generationPreservedAcrossRemovalAndReappearance() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        insertExercises(
            exerciseFixture(id = R05B_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = R05B_ACCOUNT),
        )
        insertWorkoutAggregate(
            workoutFixture(id = R05B_WORKOUT_A).copy(accountId = R05B_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = R05B_ENTRY_A1,
                    workoutId = R05B_WORKOUT_A,
                    exerciseId = R05B_CUSTOM_A,
                ),
            ),
            listOf(workoutSetFixture(id = R05B_SET_A1, workoutExerciseId = R05B_ENTRY_A1)),
        )
        insertWorkoutAggregate(
            workoutFixture(id = R05C_WORKOUT_B).copy(accountId = R05B_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = R05C_ENTRY_X1,
                    workoutId = R05C_WORKOUT_B,
                    exerciseId = R05C_BUNDLED,
                ),
            ),
            emptyList(),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 5 WHERE id = ?",
            arrayOf(R05B_WORKOUT_A),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 9 WHERE id = ?",
            arrayOf(R05C_WORKOUT_B),
        )
        val first = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        val kept = database.r05cQuery(
            "SELECT local_generation, sync_state, server_revision FROM workouts WHERE id = ?",
            arrayOf(R05B_WORKOUT_A),
        ).single()
        assertEquals(5L, kept["local_generation"])
        assertEquals("synced", kept["sync_state"])
        assertEquals(1L, kept["server_revision"])
        assertTrue(
            database.r05cQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(R05C_WORKOUT_B)).isEmpty(),
        )
        val reservation = database.syncCanonicalDao().getRoot(R05B_ACCOUNT, R05B_TYPE_WORKOUT, R05C_WORKOUT_B)!!
        assertEquals("unobserved", reservation.canonicalState)
        assertEquals(9L, reservation.localGenerationWatermark)

        database.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_2,
                cursor = R05B_CURSOR_2,
                changes = r05bJsonArray(
                    r05bChange(R05B_TYPE_WORKOUT, R05B_WORKOUT_A, R05B_OP_DELETE, 2L, null),
                ),
            ),
            1L,
        )
        val second = database.r05cStore(barrier = r05cGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, second.status)
        assertEquals(2L, second.cacheEpoch)
        assertEquals(2L, second.materializedEpoch)
        assertTrue(
            database.r05cQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(R05B_WORKOUT_A)).isEmpty(),
        )
        val tombstone = database.syncCanonicalDao().getRoot(R05B_ACCOUNT, R05B_TYPE_WORKOUT, R05B_WORKOUT_A)!!
        assertEquals("delete", tombstone.canonicalState)
        assertEquals(2L, tombstone.serverRevision)
        assertEquals(5L, tombstone.localGenerationWatermark)

        database.r05cApplyCanonical(
            r05bPull(
                batchId = R05C_BATCH_3,
                cursor = R05C_CURSOR_3,
                changes = r05bJsonArray(
                    r05bChange(
                        R05B_TYPE_WORKOUT,
                        R05B_WORKOUT_A,
                        R05B_OP_UPSERT,
                        3L,
                        r05bWorkoutRecord(
                            name = "Reappeared",
                            revision = 3L,
                            exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                        ),
                    ),
                ),
            ),
            2L,
        )
        val third = database.r05cStore(barrier = r05cGrantBarrier()).publish(2L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, third.status)
        assertEquals(3L, third.cacheEpoch)
        val revived = database.r05cQuery(
            "SELECT name, local_generation, sync_state, server_revision FROM workouts WHERE id = ?",
            arrayOf(R05B_WORKOUT_A),
        ).single()
        assertEquals("Reappeared", revived["name"])
        assertEquals(3L, revived["server_revision"])
        assertEquals("synced", revived["sync_state"])
        assertEquals(5L, revived["local_generation"])
    }

    @Test
    fun canonicalOutboxCursorDeviceAndCatalogueUnchanged() = runTest {
        seedLookups()
        database.syncDao().upsertSyncState(SyncStateEntity(R05B_ACCOUNT, "cursor-9", 12345L))
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.r05cInsertOutbox(
            mutationId = "20000000-0000-4000-8000-000000000097",
            account = R05B_OTHER_ACCOUNT,
        )
        val canonicalBefore = database.r05cQuery(
            "SELECT account_id, aggregate_type, aggregate_id, server_revision," +
                " canonical_state, canonical_json, cache_epoch FROM sync_canonical_roots ORDER BY aggregate_id",
        )
        val watermarksBefore = database.r05cQuery(
            "SELECT aggregate_id, local_generation_watermark FROM sync_canonical_roots",
        ).associate { it["aggregate_id"] as String to it["local_generation_watermark"] as Long }
        val outboxBefore = database.r05cDump("sync_outbox")
        val syncStateBefore = database.r05cDump("sync_state")
        val deviceBefore = database.r05cDump("sync_device_state")
        val muscleBefore = database.r05cDump("muscle_groups")
        val equipmentBefore = database.r05cDump("equipment")
        val bundledBefore = database.r05cQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(R05C_BUNDLED))

        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)

        assertEquals(
            canonicalBefore,
            database.r05cQuery(
                "SELECT account_id, aggregate_type, aggregate_id, server_revision," +
                    " canonical_state, canonical_json, cache_epoch FROM sync_canonical_roots ORDER BY aggregate_id",
            ),
        )
        val watermarksAfter = database.r05cQuery(
            "SELECT aggregate_id, local_generation_watermark FROM sync_canonical_roots",
        ).associate { it["aggregate_id"] as String to it["local_generation_watermark"] as Long }
        watermarksAfter.forEach { (id, after) ->
            assertTrue("watermark regressed for $id", after >= (watermarksBefore[id] ?: 0L))
        }
        assertEquals(outboxBefore, database.r05cDump("sync_outbox"))
        assertEquals(syncStateBefore, database.r05cDump("sync_state"))
        assertEquals(deviceBefore, database.r05cDump("sync_device_state"))
        assertEquals(muscleBefore, database.r05cDump("muscle_groups"))
        assertEquals(equipmentBefore, database.r05cDump("equipment"))
        assertEquals(
            bundledBefore,
            database.r05cQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(R05C_BUNDLED)),
        )
        assertEquals(0, database.syncDao().getOutboxForAccount(R05B_ACCOUNT).size)
    }

    @Test
    fun customRenameAtUnchangedParentRevisionAdvancesSignal() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A, exerciseName = "Stale Press"),
                        ),
                    ),
                ),
            ),
        )
        val first = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        database.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_2,
                cursor = R05B_CURSOR_2,
                changes = r05bJsonArray(
                    r05bChange(
                        R05B_TYPE_CUSTOM,
                        R05B_CUSTOM_A,
                        R05B_OP_UPSERT,
                        2L,
                        customA(revision = 2L).put("name", "Fly Plus"),
                    ),
                ),
            ),
            1L,
        )
        val second = database.r05cStore(barrier = r05cGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, second.status)
        assertEquals(2L, second.cacheEpoch)
        assertEquals(2L, second.materializedEpoch)
        assertEquals(
            "Fly Plus",
            database.r05cQuery(
                "SELECT e.name AS n FROM workout_exercises we" +
                    " JOIN exercises e ON e.id = we.exercise_id WHERE we.workout_id = ?",
                arrayOf(R05B_WORKOUT_A),
            ).single()["n"],
        )
        assertEquals(
            1L,
            database.r05cQuery(
                "SELECT server_revision FROM workouts WHERE id = ?",
                arrayOf(R05B_WORKOUT_A),
            ).single()["server_revision"],
        )
        val renamedRepository = GymRepository(database, RepositorySession(R05B_ACCOUNT, R05C_GENERATION))
        val renamedDetails = renamedRepository.workoutDetails(R05B_WORKOUT_A)
        assertNotNull(renamedDetails)
        assertEquals(
            "Fly Plus",
            renamedDetails!!.exercises.single().workoutExercise.exerciseName,
        )
        assertEquals(2L, database.syncCanonicalDao().getRoot(R05B_ACCOUNT, R05B_TYPE_CUSTOM, R05B_CUSTOM_A)!!.serverRevision)
    }

    @Test
    fun faultAfterDeletesRollsBackCompleteGraph() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(
                    R05B_TYPE_ROUTINE,
                    R05B_ROUTINE_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bRoutineRecord(
                        name = "Legs",
                        isDraft = false,
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bRoutineEntry(
                                exerciseId = R05C_BUNDLED,
                                sets = r05bJsonArray(r05bRoutineSet()),
                            ),
                        ),
                    ),
                ),
            ),
        )
        database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        database.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_2,
                cursor = R05B_CURSOR_2,
                changes = r05bJsonArray(
                    r05bChange(
                        R05B_TYPE_ROUTINE,
                        R05B_ROUTINE_A,
                        R05B_OP_UPSERT,
                        2L,
                        r05bRoutineRecord(
                            name = "Legs Plus",
                            isDraft = false,
                            revision = 2L,
                            exercises = r05bJsonArray(
                                r05bRoutineEntry(
                                    exerciseId = R05C_BUNDLED,
                                    sets = r05bJsonArray(
                                        r05bRoutineSet(),
                                        r05bRoutineSet(id = R05C_SET_X1, position = 1),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            1L,
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER r05c_block_child BEFORE INSERT ON routine_sets " +
                "WHEN NEW.id = '$R05C_SET_X1' " +
                "BEGIN SELECT RAISE(ABORT, 'injected r05c fault'); END",
        )
        try {
            val before = database.r05cDumpAll()
            val lease = R05cTestLease()
            try {
                database.r05cStore(barrier = r05cGrantBarrier(lease)).publish(1L)
                fail("Expected CanonicalStorageException from injected fault")
            } catch (e: CanonicalStorageException) {
                assertSafeMessage(e.message, R05B_ACCOUNT, R05B_ROUTINE_A, R05C_SET_X1)
            }
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.r05cDumpAll())
            assertEquals(
                "Legs",
                database.r05cQuery(
                    "SELECT name FROM routines WHERE id = ?",
                    arrayOf(R05B_ROUTINE_A),
                ).single()["name"],
            )
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER r05c_block_child")
        }
    }

    @Test
    fun fileBackedReopenRetainsSignalDeletionAndProjection() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "r05c-publication-${System.nanoTime()}.db")
        var fileDb: GymDatabase? = null
        try {
            fileDb = Room.databaseBuilder(context, GymDatabase::class.java, file.absolutePath).build()
            fileDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO muscle_groups (id, slug, name) VALUES (?, ?, ?)",
                arrayOf(R05C_MUSCLE_PRIMARY, R05C_MUSCLE_PRIMARY, "Chest"),
            )
            fileDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO equipment (id, name, type, usage_type) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(R05C_EQUIPMENT, "Dumbbell", "weight", null),
            )
            fileDb.openHelper.writableDatabase.execSQL(
                """INSERT INTO exercises
                    (id, name, type, difficulty_level, force_type, mechanics, category,
                     instructions, is_user_created, account_id, server_revision,
                     local_generation, sync_state, deleted_at_millis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(
                    R05C_BUNDLED, "Bench Press", "reps", null, null, null, "strength",
                    null, 0, null, null, 0, "local_only", null,
                ),
            )
            val session = RepositorySession(R05B_ACCOUNT, R05C_GENERATION)
            fileDb.syncDao().upsertDeviceState(
                com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity(
                    activeAccountId = R05B_ACCOUNT,
                    cachedAccountId = R05B_ACCOUNT,
                    sessionGeneration = R05C_GENERATION,
                ),
            )
            fileDb.openHelper.writableDatabase.execSQL(
                """INSERT INTO sync_account_cache
                    (account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch)
                    VALUES (?, 1, 0, 0, 0)""",
                arrayOf<Any?>(R05B_ACCOUNT),
            )
            CanonicalStore(fileDb, session).applyBatch(
                r05bDecodeBatch(
                    r05bPull(
                        changes = r05bJsonArray(
                            r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                            r05bChange(R05B_TYPE_WORKOUT, R05C_WORKOUT_B, R05B_OP_DELETE, 3L, null),
                        ),
                    ),
                ),
                0L,
            )
            val published = ProjectionStore(fileDb, session, r05cGrantBarrier()).publish(0L)
            assertEquals(ProjectionPublicationStatus.PUBLISHED, published.status)
            assertEquals(1L, published.cacheEpoch)
            fileDb.close()
            fileDb = Room.databaseBuilder(context, GymDatabase::class.java, file.absolutePath).build()
            val reopenedCache = fileDb.r05cQuery(
                "SELECT baseline_complete, cache_epoch, projection_dirty, materialized_epoch" +
                    " FROM sync_account_cache WHERE account_id = ?",
                arrayOf(R05B_ACCOUNT),
            ).single()
            assertEquals(1L, reopenedCache["baseline_complete"])
            assertEquals(1L, reopenedCache["cache_epoch"])
            assertEquals(0L, reopenedCache["projection_dirty"])
            assertEquals(1L, reopenedCache["materialized_epoch"])
            assertTrue(
                fileDb.r05cQuery(
                    "SELECT id FROM exercises WHERE id = ?",
                    arrayOf(R05B_CUSTOM_A),
                ).isNotEmpty(),
            )
            val deleted = fileDb.syncCanonicalDao().getRoot(R05B_ACCOUNT, R05B_TYPE_WORKOUT, R05C_WORKOUT_B)!!
            assertEquals("delete", deleted.canonicalState)
            val retry = ProjectionStore(fileDb, session, r05cGrantBarrier()).publish(1L)
            assertEquals(ProjectionPublicationStatus.UNCHANGED, retry.status)
            fileDb.close()
            fileDb = null
        } finally {
            try {
                fileDb?.close()
            } catch (_: Exception) {
            }
            file.delete()
            File(file.absolutePath + "-wal").delete()
            File(file.absolutePath + "-shm").delete()
            File(file.absolutePath + "-journal").delete()
        }
    }

    @Test
    fun corruptRetainedEvidenceThrowsStorageExceptionUnchanged() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(r05bWorkoutEntry(exerciseId = R05B_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET canonical_json = ? " +
                "WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            arrayOf<Any?>("{corrupt", R05B_ACCOUNT, R05B_TYPE_WORKOUT, R05B_WORKOUT_A),
        )
        val before = database.r05cDumpAll()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET canonical_json = ? " +
                "WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            arrayOf<Any?>("{corrupt", R05B_ACCOUNT, R05B_TYPE_WORKOUT, R05B_WORKOUT_A),
        )
        val snapshotBeforeFault = database.r05cDumpAll()
        assertEquals(before, snapshotBeforeFault)
        val lease = R05cTestLease()
        try {
            database.r05cStore(barrier = r05cGrantBarrier(lease)).publish(0L)
            fail("Expected CanonicalStorageException for corrupt retained evidence")
        } catch (e: CanonicalStorageException) {
            assertSafeMessage(e.message, R05B_ACCOUNT, R05B_WORKOUT_A)
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.r05cDumpAll())
        assertEquals(0, database.r05cDomainCount("workouts"))
    }

    @Test
    fun snapshotAbsentEvidenceCreatesNoFakeRoot() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
            ),
        )
        database.r05bSeedCanonicalRow(
            R05B_ACCOUNT,
            R05B_TYPE_WORKOUT,
            R05C_WORKOUT_B,
            5L,
            "snapshot_absent",
            null,
            0L,
            0L,
        )
        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertTrue(
            database.r05cQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(R05C_WORKOUT_B)).isEmpty(),
        )
        assertEquals(
            "Fly",
            database.r05cQuery(
                "SELECT name FROM exercises WHERE id = ?",
                arrayOf(R05B_CUSTOM_A),
            ).single()["name"],
        )
        assertEquals(
            "snapshot_absent",
            database.syncCanonicalDao().getRoot(R05B_ACCOUNT, R05B_TYPE_WORKOUT, R05C_WORKOUT_B)!!
                .canonicalState,
        )
    }

    @Test
    fun retainedFamilyParentDisagreementReusesConflictBoundary() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                id = R05B_ENTRY_A1,
                                exerciseId = R05B_CUSTOM_A,
                                sets = r05bJsonArray(r05bWorkoutSet(id = R05B_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        database.r05bSeedCanonicalRow(
            R05B_ACCOUNT,
            R05B_TYPE_WORKOUT,
            R05B_WORKOUT_B,
            1L,
            "upsert",
            r05bWorkoutRecord(
                id = R05B_WORKOUT_B,
                revision = 1L,
                exercises = r05bJsonArray(
                    r05bWorkoutEntry(
                        id = R05B_ENTRY_A1,
                        exerciseId = R05C_BUNDLED,
                        sets = r05bJsonArray(r05bWorkoutSet(id = R05C_SET_X1)),
                    ),
                ),
            ).toString(),
            0L,
            0L,
        )
        val before = database.r05cDumpAll()
        val lease = R05cTestLease()
        try {
            database.r05cStore(barrier = r05cGrantBarrier(lease)).publish(0L)
            fail("Expected CanonicalConflictException for retained parent disagreement")
        } catch (e: CanonicalConflictException) {
            assertSafeMessage(e.message, R05B_ACCOUNT, R05B_ENTRY_A1)
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.r05cDumpAll())
        }
        assertEquals(0, database.r05cDomainCount("workouts"))
    }

    @Test
    fun crossRoleAliasFailsDespiteMatchingTargetRow() = runTest {
        seedLookups()
        insertExercises(
            exerciseFixture(id = R05B_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = R05B_ACCOUNT),
        )
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_WORKOUT,
                    R05B_WORKOUT_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bWorkoutRecord(
                        revision = 1L,
                        exercises = r05bJsonArray(
                            r05bWorkoutEntry(
                                id = R05B_ENTRY_A1,
                                exerciseId = R05B_CUSTOM_A,
                                sets = r05bJsonArray(r05bWorkoutSet(id = R05B_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture(id = R05B_WORKOUT_A).copy(accountId = R05B_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = R05B_ENTRY_A1,
                    workoutId = R05B_WORKOUT_A,
                    exerciseId = R05B_CUSTOM_A,
                ),
            ),
            emptyList(),
        )
        insertWorkoutAggregate(
            workoutFixture(id = R05C_WORKOUT_B),
            listOf(
                workoutExerciseFixture(id = R05C_ENTRY_X2, workoutId = R05C_WORKOUT_B, exerciseId = R05C_BUNDLED),
            ),
            listOf(workoutSetFixture(id = R05B_ENTRY_A1, workoutExerciseId = R05C_ENTRY_X2)),
        )
        assertPublishThrowsCollisionUnchanged()
    }

    @Test
    fun savedBlankEmptyRoutineMaterializes() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
                r05bChange(
                    R05B_TYPE_ROUTINE,
                    R05C_ROUTINE_B,
                    R05B_OP_UPSERT,
                    1L,
                    r05bRoutineRecord(id = R05C_ROUTINE_B, name = "", isDraft = false, revision = 1L),
                ),
            ),
        )
        val result = database.r05cStore(barrier = r05cGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        val saved = database.r05cQuery("SELECT * FROM routines WHERE id = ?", arrayOf(R05C_ROUTINE_B)).single()
        assertEquals("", saved["name"])
        assertEquals(0L, saved["is_draft"])
        assertEquals(R05B_ACCOUNT, saved["account_id"])
        assertEquals(1L, saved["server_revision"])
        assertEquals("synced", saved["sync_state"])
        assertTrue(
            database.r05cQuery(
                "SELECT id FROM routine_exercises WHERE routine_id = ?",
                arrayOf(R05C_ROUTINE_B),
            ).isEmpty(),
        )
    }

    @Test
    fun routineRootPkCollisionsFail() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(
                    R05B_TYPE_ROUTINE,
                    R05B_ROUTINE_A,
                    R05B_OP_UPSERT,
                    1L,
                    r05bRoutineRecord(name = "Canon", isDraft = false, revision = 1L),
                ),
            ),
        )
        insertRoutineAggregate(routineFixture(id = R05B_ROUTINE_A, name = "Local"))
        assertPublishThrowsCollisionUnchanged()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM routines WHERE id = ?",
            arrayOf(R05B_ROUTINE_A),
        )
        insertRoutineAggregate(
            routineFixture(id = R05B_ROUTINE_A, name = "Foreign").copy(accountId = R05B_OTHER_ACCOUNT),
        )
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            R05B_OTHER_ACCOUNT,
            database.r05cQuery(
                "SELECT account_id FROM routines WHERE id = ?",
                arrayOf(R05B_ROUTINE_A),
            ).single()["account_id"],
        )
    }

    @Test
    fun customPhysicalCollisionsFail() = runTest {
        seedLookups()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                r05bChange(R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L, customA()),
            ),
        )
        insertExercises(exerciseFixture(id = R05B_CUSTOM_A, name = "Local", isUserCreated = true))
        assertPublishThrowsCollisionUnchanged()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM exercises WHERE id = ?",
            arrayOf(R05B_CUSTOM_A),
        )
        insertExercises(
            exerciseFixture(id = R05B_CUSTOM_A, name = "Foreign", isUserCreated = true)
                .copy(accountId = R05B_OTHER_ACCOUNT),
        )
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            "Foreign",
            database.r05cQuery(
                "SELECT name FROM exercises WHERE id = ?",
                arrayOf(R05B_CUSTOM_A),
            ).single()["name"],
        )
    }
}
