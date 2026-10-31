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
 * Projection independent acceptance: whole-graph validation, physical collisions,
 * generation preservation, epoch signal, rollback and file-backed persistence.
 * Real Room, raw SQLite oracles, handwritten expectations from Projection-task.md.
 */
class ProjectionPublicationGraphTest : GymDatabaseTest() {

    private suspend fun seedLookups() {
        insertMuscleGroups(
            muscleGroupFixture(id = PROJECTION_MUSCLE_PRIMARY, name = "Chest"),
            muscleGroupFixture(id = PROJECTION_MUSCLE_SECONDARY, name = "Shoulders"),
        )
        insertEquipment(equipmentFixture(id = PROJECTION_EQUIPMENT, name = "Dumbbell"))
        insertExercises(exerciseFixture(id = PROJECTION_BUNDLED, name = "Bench Press"))
    }

    private fun customA(revision: Long = 1L) = canonicalCustomRecord(
        id = CANONICAL_CUSTOM_A,
        name = "Fly",
        revision = revision,
        category = "strength",
        muscleGroups = canonicalJsonArray(canonicalMuscleRef(PROJECTION_MUSCLE_PRIMARY, "Chest", "primary")),
        equipment = canonicalJsonArray(canonicalEquipmentRef(PROJECTION_EQUIPMENT, "Dumbbell", 2)),
    )

    private fun assertSafeMessage(message: String?, vararg secrets: String) {
        val text = message ?: ""
        secrets.forEach { assertTrue("message leaks domain value", !text.contains(it)) }
    }

    private suspend fun assertPublishThrowsGraphUnchanged(): ProjectionGraphException {
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionGraphException")
            throw AssertionError("unreachable")
        } catch (e: ProjectionGraphException) {
            assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_WORKOUT_A, CANONICAL_CUSTOM_A)
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.projectionDumpAll())
            return e
        }
    }

    private suspend fun assertPublishThrowsCollisionUnchanged(): ProjectionCollisionException {
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionCollisionException")
            throw AssertionError("unreachable")
        } catch (e: ProjectionCollisionException) {
            assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_WORKOUT_A, CANONICAL_CUSTOM_A)
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.projectionDumpAll())
            return e
        }
    }

    // Whole-graph dependency validation.

    @Test
    fun unknownCustomDependencyFails() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(exerciseId = "99999999-9999-4999-8999-999999999999"),
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
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_DELETE, 2L, null),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
        val root = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!
        assertEquals("delete", root.canonicalState)
    }

    @Test
    fun localOnlyCustomDependencyFails() = runTest {
        seedLookups()
        val localId = "aaaaaaaa-0000-4000-8000-00000000c106"
        insertExercises(exerciseFixture(id = localId, name = "Local", isUserCreated = true))
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = localId)),
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
                .copy(accountId = CANONICAL_OTHER_ACCOUNT),
        )
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = foreignCustom)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
        assertEquals(
            CANONICAL_OTHER_ACCOUNT,
            database.projectionQuery(
                "SELECT account_id FROM exercises WHERE id = ?",
                arrayOf(foreignCustom),
            ).single()["account_id"],
        )
    }

    @Test
    fun missingMuscleThenEquipmentFailBeforeSuccess() = runTest {
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        assertPublishThrowsGraphUnchanged()
        insertMuscleGroups(muscleGroupFixture(id = PROJECTION_MUSCLE_PRIMARY, name = "Chest"))
        assertPublishThrowsGraphUnchanged()
        insertEquipment(equipmentFixture(id = PROJECTION_EQUIPMENT, name = "Dumbbell"))
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        val muscles = database.projectionQuery(
            "SELECT muscle_group_id FROM exercise_muscle_groups WHERE exercise_id = ?",
            arrayOf(CANONICAL_CUSTOM_A),
        )
        assertEquals(listOf(PROJECTION_MUSCLE_PRIMARY), muscles.map { it["muscle_group_id"] })
    }

    @Test
    fun multipleActiveWorkoutsFail() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        name = "First",
                        status = "active",
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_B,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_B,
                        name = "Second",
                        status = "active",
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = PROJECTION_ENTRY_X1,
                                exerciseId = PROJECTION_BUNDLED,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = PROJECTION_SET_X1)),
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
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        name = "Old",
                        status = "active",
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 2L, null),
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_B,
                        CANONICAL_OP_UPSERT,
                        1L,
                        canonicalWorkoutRecord(
                            id = CANONICAL_WORKOUT_B,
                            name = "New",
                            status = "active",
                            revision = 1L,
                            exercises = canonicalJsonArray(
                                canonicalWorkoutEntry(
                                    id = PROJECTION_ENTRY_X1,
                                    exerciseId = PROJECTION_BUNDLED,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            1L,
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(2L, result.cacheEpoch)
        assertTrue(
            database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_A)).isEmpty(),
        )
        val active = database.projectionQuery("SELECT name FROM workouts WHERE status = 'active'")
        assertEquals(listOf("New"), active.map { it["name"] })
    }

    // Physical collisions.

    @Test
    fun localRootPkCollisionFails() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(workoutFixture(id = CANONICAL_WORKOUT_A, name = "Local"))
        assertPublishThrowsCollisionUnchanged()
    }

    @Test
    fun foreignRootPkCollisionFails() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(workoutFixture(id = CANONICAL_WORKOUT_A).copy(accountId = CANONICAL_OTHER_ACCOUNT))
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            CANONICAL_OTHER_ACCOUNT,
            database.projectionQuery(
                "SELECT account_id FROM workouts WHERE id = ?",
                arrayOf(CANONICAL_WORKOUT_A),
            ).single()["account_id"],
        )
    }

    @Test
    fun bundledCustomClaimCollisionFails() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_CUSTOM,
                    PROJECTION_BUNDLED,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalCustomRecord(id = PROJECTION_BUNDLED, name = "Claimed", revision = 1L),
                ),
            ),
        )
        assertPublishThrowsCollisionUnchanged()
        val bundled = database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(PROJECTION_BUNDLED)).single()
        assertEquals("Bench Press", bundled["name"])
        assertEquals(0L, bundled["is_user_created"])
    }

    @Test
    fun entryAndSetPkAndRoleCollisionsFail() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B),
            listOf(workoutExerciseFixture(id = CANONICAL_ENTRY_A1, workoutId = PROJECTION_WORKOUT_B, exerciseId = PROJECTION_BUNDLED)),
            listOf(workoutSetFixture(id = CANONICAL_SET_A1, workoutExerciseId = CANONICAL_ENTRY_A1)),
        )
        assertPublishThrowsCollisionUnchanged()

        database.openHelper.writableDatabase.execSQL("DELETE FROM workout_sets")
        database.openHelper.writableDatabase.execSQL("DELETE FROM workout_exercises")
        database.openHelper.writableDatabase.execSQL("DELETE FROM workouts")
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B),
            listOf(workoutExerciseFixture(id = PROJECTION_ENTRY_X2, workoutId = PROJECTION_WORKOUT_B, exerciseId = PROJECTION_BUNDLED)),
            listOf(workoutSetFixture(id = CANONICAL_ENTRY_A1, workoutExerciseId = PROJECTION_ENTRY_X2)),
        )
        assertPublishThrowsCollisionUnchanged()
    }

    @Test
    fun sameAccountWrongRootReuseFailsDespiteRemoval() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_B,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_B,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = PROJECTION_ENTRY_X1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B).copy(accountId = CANONICAL_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = CANONICAL_ENTRY_A1,
                    workoutId = PROJECTION_WORKOUT_B,
                    exerciseId = PROJECTION_BUNDLED,
                ),
            ),
            listOf(workoutSetFixture(id = CANONICAL_SET_A1, workoutExerciseId = CANONICAL_ENTRY_A1)),
        )
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            PROJECTION_WORKOUT_B,
            database.projectionQuery(
                "SELECT workout_id FROM workout_exercises WHERE id = ?",
                arrayOf(CANONICAL_ENTRY_A1),
            ).single()["workout_id"],
        )
    }

    @Test
    fun validSameBindingAndWorkoutRoutineIdOverlapSucceed() = runTest {
        seedLookups()
        val sharedId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaa102"
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    sharedId,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        id = sharedId,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1)),
                            ),
                        ),
                    ),
                ),
                canonicalChange(
                    CANONICAL_TYPE_ROUTINE,
                    sharedId,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalRoutineRecord(
                        id = sharedId,
                        name = "Shared",
                        isDraft = false,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalRoutineEntry(
                                id = CANONICAL_ENTRY_A2,
                                exerciseId = CANONICAL_CUSTOM_A,
                                sets = canonicalJsonArray(canonicalRoutineSet(id = CANONICAL_SET_A2)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertExercises(
            exerciseFixture(id = CANONICAL_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = CANONICAL_ACCOUNT),
        )
        insertWorkoutAggregate(
            workoutFixture(id = sharedId).copy(accountId = CANONICAL_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = CANONICAL_ENTRY_A1,
                    workoutId = sharedId,
                    exerciseId = CANONICAL_CUSTOM_A,
                ),
            ),
            listOf(workoutSetFixture(id = CANONICAL_SET_A1, workoutExerciseId = CANONICAL_ENTRY_A1)),
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertEquals(
            sharedId,
            database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(sharedId)).single()["id"],
        )
        assertEquals(
            "Shared",
            database.projectionQuery("SELECT name FROM routines WHERE id = ?", arrayOf(sharedId)).single()["name"],
        )
    }

    @Test
    fun crossScopeReferenceToRemovedCustomFailsWithoutTouchingOtherScope() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B).copy(accountId = CANONICAL_OTHER_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = PROJECTION_ENTRY_X2,
                    workoutId = PROJECTION_WORKOUT_B,
                    exerciseId = CANONICAL_CUSTOM_A,
                ),
            ),
            listOf(workoutSetFixture(id = PROJECTION_SET_X2, workoutExerciseId = PROJECTION_ENTRY_X2)),
        )
        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_DELETE, 2L, null),
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_A,
                        CANONICAL_OP_UPSERT,
                        2L,
                        canonicalWorkoutRecord(
                            revision = 2L,
                            exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = PROJECTION_BUNDLED)),
                        ),
                    ),
                ),
            ),
            1L,
        )
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(1L)
            fail("Expected safe failure for surviving cross-scope reference")
        } catch (e: ProjectionGraphException) {
            assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_CUSTOM_A)
        } catch (e: CanonicalStorageException) {
            assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_CUSTOM_A)
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
        assertEquals(
            CANONICAL_CUSTOM_A,
            database.projectionQuery(
                "SELECT exercise_id FROM workout_exercises WHERE id = ?",
                arrayOf(PROJECTION_ENTRY_X2),
            ).single()["exercise_id"],
        )
        assertEquals(
            "Fly",
            database.projectionQuery(
                "SELECT name FROM exercises WHERE id = ?",
                arrayOf(CANONICAL_CUSTOM_A),
            ).single()["name"],
        )
    }

    // Generation preservation and publication signal.

    @Test
    fun generationPreservedAcrossRemovalAndReappearance() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        insertExercises(
            exerciseFixture(id = CANONICAL_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = CANONICAL_ACCOUNT),
        )
        insertWorkoutAggregate(
            workoutFixture(id = CANONICAL_WORKOUT_A).copy(accountId = CANONICAL_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = CANONICAL_ENTRY_A1,
                    workoutId = CANONICAL_WORKOUT_A,
                    exerciseId = CANONICAL_CUSTOM_A,
                ),
            ),
            listOf(workoutSetFixture(id = CANONICAL_SET_A1, workoutExerciseId = CANONICAL_ENTRY_A1)),
        )
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B).copy(accountId = CANONICAL_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = PROJECTION_ENTRY_X1,
                    workoutId = PROJECTION_WORKOUT_B,
                    exerciseId = PROJECTION_BUNDLED,
                ),
            ),
            emptyList(),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 5 WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 9 WHERE id = ?",
            arrayOf(PROJECTION_WORKOUT_B),
        )
        val first = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        val kept = database.projectionQuery(
            "SELECT local_generation, sync_state, server_revision FROM workouts WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        ).single()
        assertEquals(5L, kept["local_generation"])
        assertEquals("synced", kept["sync_state"])
        assertEquals(1L, kept["server_revision"])
        assertTrue(
            database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(PROJECTION_WORKOUT_B)).isEmpty(),
        )
        val reservation = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, PROJECTION_WORKOUT_B)!!
        assertEquals("unobserved", reservation.canonicalState)
        assertEquals(9L, reservation.localGenerationWatermark)

        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 2L, null),
                ),
            ),
            1L,
        )
        val second = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, second.status)
        assertEquals(2L, second.cacheEpoch)
        assertEquals(2L, second.materializedEpoch)
        assertTrue(
            database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_A)).isEmpty(),
        )
        val tombstone = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals("delete", tombstone.canonicalState)
        assertEquals(2L, tombstone.serverRevision)
        assertEquals(5L, tombstone.localGenerationWatermark)

        database.projectionApplyCanonical(
            canonicalPull(
                batchId = PROJECTION_BATCH_3,
                cursor = PROJECTION_CURSOR_3,
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_A,
                        CANONICAL_OP_UPSERT,
                        3L,
                        canonicalWorkoutRecord(
                            name = "Reappeared",
                            revision = 3L,
                            exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                        ),
                    ),
                ),
            ),
            2L,
        )
        val third = database.projectionStore(barrier = projectionGrantBarrier()).publish(2L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, third.status)
        assertEquals(3L, third.cacheEpoch)
        val revived = database.projectionQuery(
            "SELECT name, local_generation, sync_state, server_revision FROM workouts WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        ).single()
        assertEquals("Reappeared", revived["name"])
        assertEquals(3L, revived["server_revision"])
        assertEquals("synced", revived["sync_state"])
        assertEquals(5L, revived["local_generation"])
    }

    @Test
    fun canonicalOutboxCursorDeviceAndCatalogueUnchanged() = runTest {
        seedLookups()
        database.syncDao().upsertSyncState(SyncStateEntity(CANONICAL_ACCOUNT, "cursor-9", 12345L))
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.projectionInsertOutbox(
            mutationId = "20000000-0000-4000-8000-000000000097",
            account = CANONICAL_OTHER_ACCOUNT,
        )
        val canonicalBefore = database.projectionQuery(
            "SELECT account_id, aggregate_type, aggregate_id, server_revision," +
                " canonical_state, canonical_json, cache_epoch FROM sync_canonical_roots ORDER BY aggregate_id",
        )
        val watermarksBefore = database.projectionQuery(
            "SELECT aggregate_id, local_generation_watermark FROM sync_canonical_roots",
        ).associate { it["aggregate_id"] as String to it["local_generation_watermark"] as Long }
        val outboxBefore = database.projectionDump("sync_outbox")
        val syncStateBefore = database.projectionDump("sync_state")
        val deviceBefore = database.projectionDump("sync_device_state")
        val muscleBefore = database.projectionDump("muscle_groups")
        val equipmentBefore = database.projectionDump("equipment")
        val bundledBefore = database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(PROJECTION_BUNDLED))

        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)

        assertEquals(
            canonicalBefore,
            database.projectionQuery(
                "SELECT account_id, aggregate_type, aggregate_id, server_revision," +
                    " canonical_state, canonical_json, cache_epoch FROM sync_canonical_roots ORDER BY aggregate_id",
            ),
        )
        val watermarksAfter = database.projectionQuery(
            "SELECT aggregate_id, local_generation_watermark FROM sync_canonical_roots",
        ).associate { it["aggregate_id"] as String to it["local_generation_watermark"] as Long }
        watermarksAfter.forEach { (id, after) ->
            assertTrue("watermark regressed for $id", after >= (watermarksBefore[id] ?: 0L))
        }
        assertEquals(outboxBefore, database.projectionDump("sync_outbox"))
        assertEquals(syncStateBefore, database.projectionDump("sync_state"))
        assertEquals(deviceBefore, database.projectionDump("sync_device_state"))
        assertEquals(muscleBefore, database.projectionDump("muscle_groups"))
        assertEquals(equipmentBefore, database.projectionDump("equipment"))
        assertEquals(
            bundledBefore,
            database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(PROJECTION_BUNDLED)),
        )
        assertEquals(0, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
    }

    @Test
    fun customRenameAtUnchangedParentRevisionAdvancesSignal() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A, exerciseName = "Stale Press"),
                        ),
                    ),
                ),
            ),
        )
        val first = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_CUSTOM,
                        CANONICAL_CUSTOM_A,
                        CANONICAL_OP_UPSERT,
                        2L,
                        customA(revision = 2L).put("name", "Fly Plus"),
                    ),
                ),
            ),
            1L,
        )
        val second = database.projectionStore(barrier = projectionGrantBarrier()).publish(1L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, second.status)
        assertEquals(2L, second.cacheEpoch)
        assertEquals(2L, second.materializedEpoch)
        assertEquals(
            "Fly Plus",
            database.projectionQuery(
                "SELECT e.name AS n FROM workout_exercises we" +
                    " JOIN exercises e ON e.id = we.exercise_id WHERE we.workout_id = ?",
                arrayOf(CANONICAL_WORKOUT_A),
            ).single()["n"],
        )
        assertEquals(
            1L,
            database.projectionQuery(
                "SELECT server_revision FROM workouts WHERE id = ?",
                arrayOf(CANONICAL_WORKOUT_A),
            ).single()["server_revision"],
        )
        val renamedRepository = GymRepository(database, RepositorySession(CANONICAL_ACCOUNT, PROJECTION_GENERATION))
        val renamedDetails = renamedRepository.workoutDetails(CANONICAL_WORKOUT_A)
        assertNotNull(renamedDetails)
        assertEquals(
            "Fly Plus",
            renamedDetails!!.exercises.single().workoutExercise.exerciseName,
        )
        assertEquals(2L, database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.serverRevision)
    }

    @Test
    fun faultAfterDeletesRollsBackCompleteGraph() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_ROUTINE,
                    CANONICAL_ROUTINE_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalRoutineRecord(
                        name = "Legs",
                        isDraft = false,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalRoutineEntry(
                                exerciseId = PROJECTION_BUNDLED,
                                sets = canonicalJsonArray(canonicalRoutineSet()),
                            ),
                        ),
                    ),
                ),
            ),
        )
        database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_ROUTINE,
                        CANONICAL_ROUTINE_A,
                        CANONICAL_OP_UPSERT,
                        2L,
                        canonicalRoutineRecord(
                            name = "Legs Plus",
                            isDraft = false,
                            revision = 2L,
                            exercises = canonicalJsonArray(
                                canonicalRoutineEntry(
                                    exerciseId = PROJECTION_BUNDLED,
                                    sets = canonicalJsonArray(
                                        canonicalRoutineSet(),
                                        canonicalRoutineSet(id = PROJECTION_SET_X1, position = 1),
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
            "CREATE TRIGGER projection_block_child BEFORE INSERT ON routine_sets " +
                "WHEN NEW.id = '$PROJECTION_SET_X1' " +
                "BEGIN SELECT RAISE(ABORT, 'injected projection fault'); END",
        )
        try {
            val before = database.projectionDumpAll()
            val lease = ProjectionTestLease()
            try {
                database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(1L)
                fail("Expected CanonicalStorageException from injected fault")
            } catch (e: CanonicalStorageException) {
                assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_ROUTINE_A, PROJECTION_SET_X1)
            }
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.projectionDumpAll())
            assertEquals(
                "Legs",
                database.projectionQuery(
                    "SELECT name FROM routines WHERE id = ?",
                    arrayOf(CANONICAL_ROUTINE_A),
                ).single()["name"],
            )
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER projection_block_child")
        }
    }

    @Test
    fun fileBackedReopenRetainsSignalDeletionAndProjection() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "projection-publication-${System.nanoTime()}.db")
        var fileDb: GymDatabase? = null
        try {
            fileDb = Room.databaseBuilder(context, GymDatabase::class.java, file.absolutePath).build()
            fileDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO muscle_groups (id, slug, name) VALUES (?, ?, ?)",
                arrayOf(PROJECTION_MUSCLE_PRIMARY, PROJECTION_MUSCLE_PRIMARY, "Chest"),
            )
            fileDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO equipment (id, name, type, usage_type) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(PROJECTION_EQUIPMENT, "Dumbbell", "weight", null),
            )
            fileDb.openHelper.writableDatabase.execSQL(
                """INSERT INTO exercises
                    (id, name, type, difficulty_level, force_type, mechanics, category,
                     instructions, is_user_created, account_id, server_revision,
                     local_generation, sync_state, deleted_at_millis)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(
                    PROJECTION_BUNDLED, "Bench Press", "reps", null, null, null, "strength",
                    null, 0, null, null, 0, "local_only", null,
                ),
            )
            val session = RepositorySession(CANONICAL_ACCOUNT, PROJECTION_GENERATION)
            fileDb.syncDao().upsertDeviceState(
                com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity(
                    activeAccountId = CANONICAL_ACCOUNT,
                    cachedAccountId = CANONICAL_ACCOUNT,
                    sessionGeneration = PROJECTION_GENERATION,
                ),
            )
            fileDb.openHelper.writableDatabase.execSQL(
                """INSERT INTO sync_account_cache
                    (account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch)
                    VALUES (?, 1, 0, 0, 0)""",
                arrayOf<Any?>(CANONICAL_ACCOUNT),
            )
            CanonicalStore(fileDb, session).applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        changes = canonicalJsonArray(
                            canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                            canonicalChange(CANONICAL_TYPE_WORKOUT, PROJECTION_WORKOUT_B, CANONICAL_OP_DELETE, 3L, null),
                        ),
                    ),
                ),
                0L,
            )
            val published = ProjectionStore(fileDb, session, projectionGrantBarrier()).publish(0L)
            assertEquals(ProjectionPublicationStatus.PUBLISHED, published.status)
            assertEquals(1L, published.cacheEpoch)
            fileDb.close()
            fileDb = Room.databaseBuilder(context, GymDatabase::class.java, file.absolutePath).build()
            val reopenedCache = fileDb.projectionQuery(
                "SELECT baseline_complete, cache_epoch, projection_dirty, materialized_epoch" +
                    " FROM sync_account_cache WHERE account_id = ?",
                arrayOf(CANONICAL_ACCOUNT),
            ).single()
            assertEquals(1L, reopenedCache["baseline_complete"])
            assertEquals(1L, reopenedCache["cache_epoch"])
            assertEquals(0L, reopenedCache["projection_dirty"])
            assertEquals(1L, reopenedCache["materialized_epoch"])
            assertTrue(
                fileDb.projectionQuery(
                    "SELECT id FROM exercises WHERE id = ?",
                    arrayOf(CANONICAL_CUSTOM_A),
                ).isNotEmpty(),
            )
            val deleted = fileDb.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, PROJECTION_WORKOUT_B)!!
            assertEquals("delete", deleted.canonicalState)
            val retry = ProjectionStore(fileDb, session, projectionGrantBarrier()).publish(1L)
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
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A)),
                    ),
                ),
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET canonical_json = ? " +
                "WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            arrayOf<Any?>("{corrupt", CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A),
        )
        val before = database.projectionDumpAll()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET canonical_json = ? " +
                "WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            arrayOf<Any?>("{corrupt", CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A),
        )
        val snapshotBeforeFault = database.projectionDumpAll()
        assertEquals(before, snapshotBeforeFault)
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected CanonicalStorageException for corrupt retained evidence")
        } catch (e: CanonicalStorageException) {
            assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_WORKOUT_A)
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
        assertEquals(0, database.projectionDomainCount("workouts"))
    }

    @Test
    fun snapshotAbsentEvidenceCreatesNoFakeRoot() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
            ),
        )
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT,
            CANONICAL_TYPE_WORKOUT,
            PROJECTION_WORKOUT_B,
            5L,
            "snapshot_absent",
            null,
            0L,
            0L,
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertTrue(
            database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(PROJECTION_WORKOUT_B)).isEmpty(),
        )
        assertEquals(
            "Fly",
            database.projectionQuery(
                "SELECT name FROM exercises WHERE id = ?",
                arrayOf(CANONICAL_CUSTOM_A),
            ).single()["name"],
        )
        assertEquals(
            "snapshot_absent",
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, PROJECTION_WORKOUT_B)!!
                .canonicalState,
        )
    }

    @Test
    fun retainedFamilyParentDisagreementReusesConflictBoundary() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT,
            CANONICAL_TYPE_WORKOUT,
            CANONICAL_WORKOUT_B,
            1L,
            "upsert",
            canonicalWorkoutRecord(
                id = CANONICAL_WORKOUT_B,
                revision = 1L,
                exercises = canonicalJsonArray(
                    canonicalWorkoutEntry(
                        id = CANONICAL_ENTRY_A1,
                        exerciseId = PROJECTION_BUNDLED,
                        sets = canonicalJsonArray(canonicalWorkoutSet(id = PROJECTION_SET_X1)),
                    ),
                ),
            ).toString(),
            0L,
            0L,
        )
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected CanonicalConflictException for retained parent disagreement")
        } catch (e: CanonicalConflictException) {
            assertSafeMessage(e.message, CANONICAL_ACCOUNT, CANONICAL_ENTRY_A1)
            assertEquals(1, lease.closeCount)
            assertEquals(before, database.projectionDumpAll())
        }
        assertEquals(0, database.projectionDomainCount("workouts"))
    }

    @Test
    fun crossRoleAliasFailsDespiteMatchingTargetRow() = runTest {
        seedLookups()
        insertExercises(
            exerciseFixture(id = CANONICAL_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = CANONICAL_ACCOUNT),
        )
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        insertWorkoutAggregate(
            workoutFixture(id = CANONICAL_WORKOUT_A).copy(accountId = CANONICAL_ACCOUNT),
            listOf(
                workoutExerciseFixture(
                    id = CANONICAL_ENTRY_A1,
                    workoutId = CANONICAL_WORKOUT_A,
                    exerciseId = CANONICAL_CUSTOM_A,
                ),
            ),
            emptyList(),
        )
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B),
            listOf(
                workoutExerciseFixture(id = PROJECTION_ENTRY_X2, workoutId = PROJECTION_WORKOUT_B, exerciseId = PROJECTION_BUNDLED),
            ),
            listOf(workoutSetFixture(id = CANONICAL_ENTRY_A1, workoutExerciseId = PROJECTION_ENTRY_X2)),
        )
        assertPublishThrowsCollisionUnchanged()
    }

    @Test
    fun savedBlankEmptyRoutineMaterializes() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_ROUTINE,
                    PROJECTION_ROUTINE_B,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalRoutineRecord(id = PROJECTION_ROUTINE_B, name = "", isDraft = false, revision = 1L),
                ),
            ),
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        val saved = database.projectionQuery("SELECT * FROM routines WHERE id = ?", arrayOf(PROJECTION_ROUTINE_B)).single()
        assertEquals("", saved["name"])
        assertEquals(0L, saved["is_draft"])
        assertEquals(CANONICAL_ACCOUNT, saved["account_id"])
        assertEquals(1L, saved["server_revision"])
        assertEquals("synced", saved["sync_state"])
        assertTrue(
            database.projectionQuery(
                "SELECT id FROM routine_exercises WHERE routine_id = ?",
                arrayOf(PROJECTION_ROUTINE_B),
            ).isEmpty(),
        )
    }

    @Test
    fun routineRootPkCollisionsFail() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_ROUTINE,
                    CANONICAL_ROUTINE_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalRoutineRecord(name = "Canon", isDraft = false, revision = 1L),
                ),
            ),
        )
        insertRoutineAggregate(routineFixture(id = CANONICAL_ROUTINE_A, name = "Local"))
        assertPublishThrowsCollisionUnchanged()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM routines WHERE id = ?",
            arrayOf(CANONICAL_ROUTINE_A),
        )
        insertRoutineAggregate(
            routineFixture(id = CANONICAL_ROUTINE_A, name = "Foreign").copy(accountId = CANONICAL_OTHER_ACCOUNT),
        )
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            CANONICAL_OTHER_ACCOUNT,
            database.projectionQuery(
                "SELECT account_id FROM routines WHERE id = ?",
                arrayOf(CANONICAL_ROUTINE_A),
            ).single()["account_id"],
        )
    }

    @Test
    fun customPhysicalCollisionsFail() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
            ),
        )
        insertExercises(exerciseFixture(id = CANONICAL_CUSTOM_A, name = "Local", isUserCreated = true))
        assertPublishThrowsCollisionUnchanged()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM exercises WHERE id = ?",
            arrayOf(CANONICAL_CUSTOM_A),
        )
        insertExercises(
            exerciseFixture(id = CANONICAL_CUSTOM_A, name = "Foreign", isUserCreated = true)
                .copy(accountId = CANONICAL_OTHER_ACCOUNT),
        )
        assertPublishThrowsCollisionUnchanged()
        assertEquals(
            "Foreign",
            database.projectionQuery(
                "SELECT name FROM exercises WHERE id = ?",
                arrayOf(CANONICAL_CUSTOM_A),
            ).single()["name"],
        )
    }
}
