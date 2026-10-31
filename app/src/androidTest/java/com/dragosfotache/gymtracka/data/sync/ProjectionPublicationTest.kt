package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.gym.routineFixture
import com.dragosfotache.gymtracka.data.gym.workoutFixture
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionGraphException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import android.database.sqlite.SQLiteException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Projection independent publication acceptance (barrier, fences, protection,
 * baseline, fidelity). Real in-memory Room, no DAO mocks.
 */
class ProjectionPublicationTest : GymDatabaseTest() {

    private suspend fun seedLookups() {
        insertMuscleGroups(
            muscleGroupFixture(id = PROJECTION_MUSCLE_PRIMARY, name = "Chest"),
            muscleGroupFixture(id = PROJECTION_MUSCLE_SECONDARY, name = "Shoulders"),
        )
        insertEquipment(equipmentFixture(id = PROJECTION_EQUIPMENT, name = "Dumbbell"))
        insertExercises(exerciseFixture(id = PROJECTION_BUNDLED, name = "Bench Press"))
    }

    private fun customA(revision: Long = 1L, name: String = "Fly") = canonicalCustomRecord(
        id = CANONICAL_CUSTOM_A,
        name = name,
        revision = revision,
        category = "strength",
        difficultyLevel = "intermediate",
        forceType = "push",
        mechanics = "compound",
        instructions = canonicalJsonArray().let {
            it.put("Set up")
            it.put("Press to lockout")
            it
        },
        muscleGroups = canonicalJsonArray(
            canonicalMuscleRef(PROJECTION_MUSCLE_PRIMARY, "Chest", "primary"),
            canonicalMuscleRef(PROJECTION_MUSCLE_SECONDARY, "Shoulders", "secondary"),
        ),
        equipment = canonicalJsonArray(canonicalEquipmentRef(PROJECTION_EQUIPMENT, "Dumbbell", 2)),
    )

    private fun simpleWorkoutChange() = canonicalChange(
        CANONICAL_TYPE_WORKOUT,
        CANONICAL_WORKOUT_A,
        CANONICAL_OP_UPSERT,
        1L,
        canonicalWorkoutRecord(
            revision = 1L,
            exercises = canonicalJsonArray(
                canonicalWorkoutEntry(
                    exerciseId = CANONICAL_CUSTOM_A,
                    exerciseName = "Stale Press",
                    sets = canonicalJsonArray(canonicalWorkoutSet(repetitions = 8, weightKg = 50.0)),
                ),
            ),
        ),
    )

    private suspend fun prepareSimpleGraph() {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                simpleWorkoutChange(),
            ),
        )
    }

    // Constructor and argument validation.

    @Test
    fun constructorRejectsNullAccountOrNonpositiveGeneration() = runTest {
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease)
        try {
            database.projectionStore(account = null, barrier = barrier)
            fail("Expected IllegalArgumentException for null account")
        } catch (_: IllegalArgumentException) {
        }
        try {
            database.projectionStore(generation = 0L, barrier = barrier)
            fail("Expected IllegalArgumentException for zero generation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            database.projectionStore(generation = -3L, barrier = barrier)
            fail("Expected IllegalArgumentException for negative generation")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, database.projectionDump("sync_account_cache").size)
    }

    @Test
    fun negativeExpectedEpochRejectsBeforeBarrierAcquisition() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease)
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(barrier = barrier).publish(-1L)
            fail("Expected IllegalArgumentException for negative epoch")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(barrier.requests.isEmpty())
        assertEquals(0, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    // Session and epoch fences.

    @Test
    fun staleAccountFailsWithoutWritesAndReleasesLeaseOnce() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease)
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(account = CANONICAL_OTHER_ACCOUNT, barrier = barrier).publish(0L)
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun staleGenerationFailsWithoutWrites() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(generation = PROJECTION_GENERATION - 1, barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun missingDeviceFailsWithoutWrites() = runTest {
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected StaleSessionException for missing device state")
        } catch (_: StaleSessionException) {
        }
        assertEquals(1, lease.closeCount)
        assertEquals(0, database.projectionDump("sync_canonical_roots").size)
        assertEquals(0, database.projectionDump("sync_account_cache").size)
    }

    @Test
    fun wrongCacheEpochFailsStale() = runTest {
        prepareSimpleGraph()
        database.projectionSetCache(CANONICAL_ACCOUNT, cacheEpoch = 1L)
        val lease = ProjectionTestLease()
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected StaleCanonicalEpochException")
        } catch (_: StaleCanonicalEpochException) {
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun malformedAccountCountersRejectSafely() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease)
        database.projectionSetCache(CANONICAL_ACCOUNT, cacheEpoch = -1L)
        try {
            database.projectionStore(barrier = barrier).publish(-1L)
            fail("Expected rejection for negative cache epoch counter")
        } catch (_: IllegalArgumentException) {
        }
        database.projectionSetCache(CANONICAL_ACCOUNT, cacheEpoch = 0L, materializedEpoch = 5L)
        try {
            database.projectionStore(barrier = barrier).publish(0L)
            fail("Expected CanonicalStorageException for materialized greater than cache")
        } catch (_: CanonicalStorageException) {
        }
        assertEquals(0, database.projectionDomainCount("workouts"))
        assertEquals(0, database.projectionDomainCount("exercises") - 1)
        database.projectionSetCache(CANONICAL_ACCOUNT, materializedEpoch = 0L)
        val result = database.projectionStore(barrier = barrier).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertEquals(1L, result.materializedEpoch)
    }

    @Test
    fun epochOverflowAbortsWithoutWrapping() = runTest {
        seedLookups()
        database.projectionSeedDevice()
        database.projectionSeedCache(CANONICAL_ACCOUNT, true, Long.MAX_VALUE, false, Long.MAX_VALUE)
        database.projectionApplyCanonical(
            canonicalPull(
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                    simpleWorkoutChange(),
                ),
            ),
            Long.MAX_VALUE,
        )
        val lease = ProjectionTestLease()
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(Long.MAX_VALUE)
            fail("Expected checked overflow to abort publication")
        } catch (e: CanonicalStorageException) {
            assertTrue(!(e.message ?: "").contains(CANONICAL_ACCOUNT))
            assertTrue(!(e.message ?: "").contains(CANONICAL_WORKOUT_A))
            assertNull(e.cause)
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
        assertEquals(0, database.projectionDomainCount("workouts"))
    }

    // Barrier behavior.

    @Test
    fun acquireFailurePropagatesWithNoWrites() = runTest {
        prepareSimpleGraph()
        val injected = IllegalStateException("injected drain failure")
        val barrier = ProjectionPublicationBarrier { throw injected }
        val before = database.projectionDumpAll()
        val caught = try {
            database.projectionStore(barrier = barrier).publish(0L)
            fail("Expected injected acquire failure")
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(caught === injected)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun acquireCancellationStaysCancelledWithNoWrites() = runTest {
        prepareSimpleGraph()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<ProjectionTestLease>()
        val barrier = ProjectionPublicationBarrier {
            entered.complete(Unit)
            gate.await()
        }
        val before = database.projectionDumpAll()
        val job = launch { database.projectionStore(barrier = barrier).publish(0L) }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun revokedLeaseFailsWithoutWritesAndClosesOnce() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        lease.revoke()
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionLeaseInvalidException")
        } catch (_: ProjectionLeaseInvalidException) {
        }
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun leaseClosedExactlyOnceOnSuccess() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease)
        val result = database.projectionStore(barrier = barrier).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1, lease.closeCount)
        val request = barrier.requests.single()
        assertEquals(CANONICAL_ACCOUNT, request.accountId)
        assertEquals(PROJECTION_GENERATION, request.sessionGeneration)
        assertEquals(0L, request.expectedCacheEpoch)
    }

    @Test
    fun realCaptureDuringAcquireProtectsAndPreservesHigherGeneration() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                simpleWorkoutChange(),
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET local_generation_watermark = 23 " +
                "WHERE account_id = ? AND aggregate_id = ?",
            arrayOf(CANONICAL_ACCOUNT, CANONICAL_WORKOUT_A),
        )
        val firstLease = ProjectionTestLease()
        val first = database.projectionStore(barrier = projectionGrantBarrier(firstLease)).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        val generationBefore = database.projectionQuery(
            "SELECT local_generation FROM workouts WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        ).single()["local_generation"] as Long
        assertEquals(23L, generationBefore)

        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease) {
            val repository = GymRepository(
                database,
                RepositorySession(CANONICAL_ACCOUNT, PROJECTION_GENERATION),
                newMutationId = { "10000000-0000-4000-8000-000000000099" },
            )
            repository.updateWorkoutName(CANONICAL_WORKOUT_A, "Edited During Drain")
        }
        val result = database.projectionStore(barrier = barrier).publish(1L)
        assertEquals(ProjectionPublicationStatus.PROTECTED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertEquals(1L, result.materializedEpoch)
        assertEquals(1, lease.closeCount)
        val edited = database.projectionQuery(
            "SELECT name, local_generation FROM workouts WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        ).single()
        assertEquals("Edited During Drain", edited["name"])
        assertEquals(24L, edited["local_generation"])
        val outbox = database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT)
        assertEquals(1, outbox.size)
        assertEquals(CANONICAL_WORKOUT_A, outbox.single().aggregateId)
        val watermark = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
            .localGenerationWatermark
        assertEquals(watermark, outbox.single().localGeneration)
        assertEquals("10000000-0000-4000-8000-000000000099", outbox.single().mutationId)
        assertEquals(CANONICAL_ACCOUNT, outbox.single().accountId)
        assertEquals("pending", outbox.single().state)
        assertEquals(1, outbox.single().payloadVersion)
        assertEquals(edited["local_generation"], outbox.single().localGeneration)
        assertEquals(24L, outbox.single().localGeneration)
        assertEquals(
            24L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
                .localGenerationWatermark,
        )
        assertEquals(ProjectionCacheRow(true, 1L, true, 1L), database.projectionReadCache(CANONICAL_ACCOUNT))
    }

    // Whole-account protection and baseline.

    @Test
    fun wholeAccountProtectionAcrossAllOutboxStates() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_DELETE,
                    2L,
                    null,
                ),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_B,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_B,
                        name = "Replacement",
                        status = "active",
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A, exerciseName = "Stale"),
                        ),
                    ),
                ),
            ),
        )
        val states = listOf("pending", "frozen", "rejected", "corrupt", "unsupported", "mystery-state")
        states.forEachIndexed { index, state ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            database.projectionInsertOutbox(
                mutationId = "20000000-0000-4000-8000-0000000000${10 + index}",
                aggregateId = if (index % 2 == 0) CANONICAL_WORKOUT_B else CANONICAL_CUSTOM_A,
                aggregateType = if (index % 2 == 0) CANONICAL_TYPE_WORKOUT else CANONICAL_TYPE_CUSTOM,
                state = state,
                payloadJson = if (state == "corrupt") "{invalid" else "{\"v\":1}",
                localGeneration = 1L + index,
            )
            val lease = ProjectionTestLease()
            val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            assertEquals("state=$state", ProjectionPublicationStatus.PROTECTED, result.status)
            assertEquals("state=$state", 0L, result.cacheEpoch)
            assertEquals("state=$state", 0L, result.materializedEpoch)
            assertEquals("state=$state", 1, lease.closeCount)
        }
        assertEquals(0, database.projectionDomainCount("workouts"))
        assertEquals(0, database.projectionDomainCount("routines"))
        assertEquals(1, database.projectionDomainCount("exercises"))
        assertEquals(ProjectionCacheRow(true, 0L, true, 0L), database.projectionReadCache(CANONICAL_ACCOUNT))
        val canonical = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!
        assertEquals(1L, canonical.serverRevision)
        assertEquals("upsert", canonical.canonicalState)
    }

    @Test
    fun foreignAccountOutboxDoesNotBlock() = runTest {
        prepareSimpleGraph()
        database.projectionInsertOutbox(
            mutationId = "20000000-0000-4000-8000-000000000099",
            account = CANONICAL_OTHER_ACCOUNT,
            state = "pending",
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertNotNull(database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_A)).singleOrNull())
        assertEquals(1, database.syncDao().getOutboxForAccount(CANONICAL_OTHER_ACCOUNT).size)
        assertEquals(0, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
    }

    @Test
    fun missingCacheReturnsIncompleteBaselineWithNoWrites() = runTest {
        database.projectionSeedDevice()
        val lease = ProjectionTestLease()
        val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
        assertEquals(ProjectionPublicationStatus.INCOMPLETE_BASELINE, result.status)
        assertEquals(0L, result.cacheEpoch)
        assertEquals(0L, result.materializedEpoch)
        assertEquals(1, lease.closeCount)
        assertNull(database.projectionReadCache(CANONICAL_ACCOUNT))
        assertEquals(0, database.projectionDomainCount("workouts"))
    }

    @Test
    fun incompleteBaselineReturnsIncompleteWithNoWrites() = runTest {
        database.projectionSeedDevice()
        database.projectionSeedCache(CANONICAL_ACCOUNT, false, 0L, false, 0L)
        database.projectionApplyCanonical(
            canonicalPull(changes = canonicalJsonArray(simpleWorkoutChange())),
            0L,
        )
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
        assertEquals(ProjectionPublicationStatus.INCOMPLETE_BASELINE, result.status)
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun protectedBeatsIncompleteBaseline() = runTest {
        database.projectionSeedDevice()
        database.projectionSeedCache(CANONICAL_ACCOUNT, false, 0L, false, 0L)
        database.projectionInsertOutbox(mutationId = "20000000-0000-4000-8000-000000000098")
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PROTECTED, result.status)
        assertEquals(false, database.projectionReadCache(CANONICAL_ACCOUNT)?.baselineComplete)
    }

    // Full three-family fidelity.

    @Test
    fun fullThreeFamilyGraphFidelity() = runTest {
        seedLookups()
        insertEquipment(equipmentFixture(id = PROJECTION_EQUIPMENT_2, name = "Bench"))
        database.syncDao().upsertSyncState(SyncStateEntity(CANONICAL_ACCOUNT, "cursor-9", 12345L))
        val startedMillis = Instant.parse(CANONICAL_STARTED_AT).toEpochMilli()
        val finishedMillis = Instant.parse(CANONICAL_FINISHED_AT).toEpochMilli()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(
                    CANONICAL_TYPE_CUSTOM,
                    CANONICAL_CUSTOM_B,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalCustomRecord(id = CANONICAL_CUSTOM_B, name = "Curl", revision = 1L),
                ),
                canonicalChange(
                    CANONICAL_TYPE_CUSTOM,
                    PROJECTION_CUSTOM_C,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalCustomRecord(
                        id = PROJECTION_CUSTOM_C,
                        name = "Third",
                        revision = 1L,
                        instructions = JSONArray(),
                        equipment = canonicalJsonArray(
                            canonicalEquipmentRef(PROJECTION_EQUIPMENT_2, "Bench", null),
                        ),
                    ),
                ),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        name = "Active",
                        status = "active",
                        startedAt = CANONICAL_STARTED_AT,
                        finishedAt = null,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                exerciseName = "Stale Press",
                                position = 0,
                                notes = "Keep",
                                restDurationSeconds = 90,
                                sets = canonicalJsonArray(
                                    canonicalWorkoutSet(
                                        id = CANONICAL_SET_A1,
                                        position = 0,
                                        repetitions = 8,
                                        weightKg = 100.0,
                                        isCompleted = true,
                                        restSeconds = 87,
                                    ),
                                    canonicalWorkoutSet(
                                        id = CANONICAL_SET_A2,
                                        position = 1,
                                        repetitions = 0,
                                        weightKg = 0.0,
                                        isCompleted = false,
                                        restSeconds = null,
                                    ),
                                    canonicalWorkoutSet(
                                        id = CANONICAL_SET_A3,
                                        position = 2,
                                        repetitions = null,
                                        weightKg = null,
                                        isCompleted = false,
                                        restSeconds = null,
                                    ),
                                ),
                            ),
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A2,
                                exerciseId = PROJECTION_BUNDLED,
                                exerciseName = "Stale Bench",
                                position = 1,
                                notes = "",
                                restDurationSeconds = 30,
                                sets = canonicalJsonArray(
                                    canonicalWorkoutSet(
                                        id = PROJECTION_SET_X2,
                                        position = 0,
                                        repetitions = Int.MAX_VALUE,
                                        weightKg = Double.MAX_VALUE,
                                        isCompleted = true,
                                        restSeconds = 0,
                                    ),
                                ),
                            ),
                        ),
                        totalVolumeKg = 800.0,
                    ),
                ),
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_B,
                    CANONICAL_OP_UPSERT,
                    2L,
                    canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_B,
                        name = "History",
                        status = "finished",
                        startedAt = CANONICAL_FINISHED_AT,
                        finishedAt = CANONICAL_EARLIER_AT,
                        revision = 2L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = PROJECTION_ENTRY_X2,
                                exerciseId = CANONICAL_CUSTOM_B,
                                exerciseName = "Curl",
                                sets = canonicalJsonArray(
                                    canonicalWorkoutSet(
                                        id = PROJECTION_SET_X1,
                                        repetitions = 5,
                                        weightKg = 60.0,
                                        isCompleted = true,
                                    ),
                                ),
                            ),
                        ),
                        totalVolumeKg = 300.0,
                    ),
                ),
                canonicalChange(
                    CANONICAL_TYPE_ROUTINE,
                    CANONICAL_ROUTINE_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalRoutineRecord(id = CANONICAL_ROUTINE_A, name = "", isDraft = true, revision = 1L),
                ),
                canonicalChange(
                    CANONICAL_TYPE_ROUTINE,
                    CANONICAL_ROUTINE_B,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalRoutineRecord(
                        id = CANONICAL_ROUTINE_B,
                        name = "Legs",
                        isDraft = false,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalRoutineEntry(
                                id = PROJECTION_ENTRY_X1,
                                exerciseId = CANONICAL_CUSTOM_A,
                                exerciseName = "Stale Press",
                                position = 0,
                                restDurationSeconds = 45,
                                sets = canonicalJsonArray(
                                    canonicalRoutineSet(id = PROJECTION_SET_X1, position = 0, repetitions = 10, weightKg = 40.0),
                                    canonicalRoutineSet(
                                        id = CANONICAL_SET_A2,
                                        position = 1,
                                        repetitions = null,
                                        weightKg = null,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val muscleBefore = database.projectionDump("muscle_groups")
        val equipmentBefore = database.projectionDump("equipment")
        val bundledBefore = database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(PROJECTION_BUNDLED))

        val lease = ProjectionTestLease()
        val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertEquals(1L, result.materializedEpoch)
        assertEquals(1, lease.closeCount)
        assertEquals(ProjectionCacheRow(true, 1L, false, 1L), database.projectionReadCache(CANONICAL_ACCOUNT))

        val active = database.projectionQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_A)).single()
        assertEquals("Active", active["name"])
        assertEquals("active", active["status"])
        assertEquals(startedMillis, active["started_at_millis"])
        assertNull(active["finished_at_millis"])
        assertEquals(CANONICAL_ACCOUNT, active["account_id"])
        assertEquals(1L, active["server_revision"])
        assertEquals("synced", active["sync_state"])
        assertNull(active["deleted_at_millis"])

        val history = database.projectionQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_B)).single()
        assertEquals("finished", history["status"])
        assertEquals(finishedMillis, history["started_at_millis"])
        assertEquals(Instant.parse(CANONICAL_EARLIER_AT).toEpochMilli(), history["finished_at_millis"])
        assertTrue((history["finished_at_millis"] as Long) < (history["started_at_millis"] as Long))

        val entries = database.projectionQuery(
            "SELECT id, exercise_id, position, notes, rest_duration_seconds FROM workout_exercises" +
                " WHERE workout_id = ? ORDER BY position",
            arrayOf(CANONICAL_WORKOUT_A),
        )
        assertEquals(2, entries.size)
        assertEquals(CANONICAL_ENTRY_A1, entries[0]["id"])
        assertEquals(CANONICAL_CUSTOM_A, entries[0]["exercise_id"])
        assertEquals(0L, entries[0]["position"])
        assertEquals("Keep", entries[0]["notes"])
        assertEquals(90L, entries[0]["rest_duration_seconds"])
        assertEquals(CANONICAL_ENTRY_A2, entries[1]["id"])
        assertEquals(PROJECTION_BUNDLED, entries[1]["exercise_id"])
        assertEquals(1L, entries[1]["position"])

        val sets = database.projectionQuery(
            "SELECT id, position, repetitions, weight_kg, is_completed, rest_seconds FROM workout_sets" +
                " WHERE workout_exercise_id = ? ORDER BY position",
            arrayOf(CANONICAL_ENTRY_A1),
        )
        assertEquals(3, sets.size)
        assertEquals(listOf(CANONICAL_SET_A1, CANONICAL_SET_A2, CANONICAL_SET_A3), sets.map { it["id"] })
        assertEquals(listOf(0L, 1L, 2L), sets.map { it["position"] })
        assertEquals(8L, sets[0]["repetitions"])
        assertEquals(100.0, sets[0]["weight_kg"])
        assertEquals(1L, sets[0]["is_completed"])
        assertEquals(87L, sets[0]["rest_seconds"])
        assertEquals(0L, sets[1]["repetitions"])
        assertEquals(0.0, sets[1]["weight_kg"])
        assertNull(sets[1]["rest_seconds"])
        assertNull(sets[2]["repetitions"])
        assertNull(sets[2]["weight_kg"])

        val bigSet = database.projectionQuery(
            "SELECT id, position, repetitions, weight_kg, rest_seconds FROM workout_sets WHERE workout_exercise_id = ?",
            arrayOf(CANONICAL_ENTRY_A2),
        ).single()
        assertEquals(PROJECTION_SET_X2, bigSet["id"])
        assertEquals(0L, bigSet["position"])
        assertEquals(Int.MAX_VALUE.toLong(), bigSet["repetitions"])
        assertEquals(Double.MAX_VALUE, bigSet["weight_kg"])
        assertEquals(0L, bigSet["rest_seconds"])

        val draft = database.projectionQuery("SELECT * FROM routines WHERE id = ?", arrayOf(CANONICAL_ROUTINE_A)).single()
        assertEquals("", draft["name"])
        assertEquals(1L, draft["is_draft"])
        assertEquals(
            0,
            database.projectionQuery("SELECT id FROM routine_exercises WHERE routine_id = ?", arrayOf(CANONICAL_ROUTINE_A)).size,
        )
        val saved = database.projectionQuery("SELECT * FROM routines WHERE id = ?", arrayOf(CANONICAL_ROUTINE_B)).single()
        assertEquals("Legs", saved["name"])
        assertEquals(0L, saved["is_draft"])
        assertEquals(CANONICAL_ACCOUNT, saved["account_id"])
        assertEquals("synced", saved["sync_state"])
        val routineSets = database.projectionQuery(
            "SELECT id, position, repetitions, weight_kg FROM routine_sets" +
                " WHERE routine_exercise_id = ? ORDER BY position",
            arrayOf(PROJECTION_ENTRY_X1),
        )
        assertEquals(2, routineSets.size)
        assertEquals(listOf(PROJECTION_SET_X1, CANONICAL_SET_A2), routineSets.map { it["id"] })
        assertEquals(listOf(0L, 1L), routineSets.map { it["position"] })
        assertEquals(10L, routineSets[0]["repetitions"])
        assertEquals(40.0, routineSets[0]["weight_kg"])
        assertNull(routineSets[1]["repetitions"])
        assertNull(routineSets[1]["weight_kg"])

        val customA = database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(CANONICAL_CUSTOM_A)).single()
        assertEquals("Fly", customA["name"])
        assertEquals("reps", customA["type"])
        assertEquals("strength", customA["category"])
        assertEquals("intermediate", customA["difficulty_level"])
        assertEquals("push", customA["force_type"])
        assertEquals("compound", customA["mechanics"])
        assertNotNull(customA["instructions"])
        assertEquals(
            listOf("Set up", "Press to lockout"),
            database.exerciseDao().getById(CANONICAL_CUSTOM_A)!!.instructions,
        )
        assertEquals(1L, customA["is_user_created"])
        assertEquals(CANONICAL_ACCOUNT, customA["account_id"])
        assertEquals(1L, customA["server_revision"])
        assertEquals("synced", customA["sync_state"])
        val customB = database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(CANONICAL_CUSTOM_B)).single()
        assertEquals("Curl", customB["name"])
        assertNull(customB["category"])
        assertNull(customB["instructions"])
        val customCInstructions = database.projectionQuery(
            "SELECT instructions FROM exercises WHERE id = ?",
            arrayOf(PROJECTION_CUSTOM_C),
        ).single()["instructions"]
        assertNotNull(customCInstructions)
        assertEquals(0, JSONArray(customCInstructions as String).length())
        assertNull(
            database.projectionQuery(
                "SELECT instructions FROM exercises WHERE id = ?",
                arrayOf(CANONICAL_CUSTOM_B),
            ).single()["instructions"],
        )
        assertNull(
            database.projectionQuery(
                "SELECT num_items FROM exercise_equipment WHERE exercise_id = ?",
                arrayOf(PROJECTION_CUSTOM_C),
            ).single()["num_items"],
        )

        val muscles = database.projectionQuery(
            "SELECT muscle_group_id, type FROM exercise_muscle_groups WHERE exercise_id = ? ORDER BY type",
            arrayOf(CANONICAL_CUSTOM_A),
        )
        assertEquals(2, muscles.size)
        assertEquals(PROJECTION_MUSCLE_PRIMARY, muscles[0]["muscle_group_id"])
        assertEquals("primary", muscles[0]["type"])
        assertEquals(PROJECTION_MUSCLE_SECONDARY, muscles[1]["muscle_group_id"])
        assertEquals("secondary", muscles[1]["type"])
        val equipment = database.projectionQuery(
            "SELECT equipment_id, num_items FROM exercise_equipment WHERE exercise_id = ?",
            arrayOf(CANONICAL_CUSTOM_A),
        ).single()
        assertEquals(PROJECTION_EQUIPMENT, equipment["equipment_id"])
        assertEquals(2L, equipment["num_items"])

        val resolvedWorkout = database.projectionQuery(
            "SELECT e.name AS exercise_name FROM workout_exercises we" +
                " JOIN exercises e ON e.id = we.exercise_id WHERE we.id = ?",
            arrayOf(CANONICAL_ENTRY_A1),
        ).single()
        assertEquals("Fly", resolvedWorkout["exercise_name"])
        val resolvedRoutine = database.projectionQuery(
            "SELECT e.name AS exercise_name FROM routine_exercises re" +
                " JOIN exercises e ON e.id = re.exercise_id WHERE re.id = ?",
            arrayOf(PROJECTION_ENTRY_X1),
        ).single()
        assertEquals("Fly", resolvedRoutine["exercise_name"])

        val boundRepository = GymRepository(database, RepositorySession(CANONICAL_ACCOUNT, PROJECTION_GENERATION))
        val publishedWorkout = boundRepository.workoutDetails(CANONICAL_WORKOUT_A)
        assertNotNull(publishedWorkout)
        assertEquals(
            "Fly",
            publishedWorkout!!.exercises.single { it.workoutExercise.id == CANONICAL_ENTRY_A1 }.workoutExercise.exerciseName,
        )
        val publishedRoutine = boundRepository.routineDetails(CANONICAL_ROUTINE_B)
        assertNotNull(publishedRoutine)
        assertEquals("Fly", publishedRoutine!!.exercises.single().routineExercise.exerciseName)

        assertEquals(muscleBefore, database.projectionDump("muscle_groups"))
        assertEquals(equipmentBefore, database.projectionDump("equipment"))
        assertEquals(bundledBefore, database.projectionQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(PROJECTION_BUNDLED)))

        val cursor = database.syncDao().getSyncState(CANONICAL_ACCOUNT)!!
        assertEquals("cursor-9", cursor.cursor)
        assertEquals(12345L, cursor.lastSuccessMillis)
        assertEquals(0, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
    }

    @Test
    fun sameIdReplacementRemovesOldChildren() = runTest {
        prepareSimpleGraph()
        database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        database.projectionApplyCanonical(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_A,
                        CANONICAL_OP_UPSERT,
                        2L,
                        canonicalWorkoutRecord(
                            name = "Trimmed",
                            revision = 2L,
                            exercises = canonicalJsonArray(
                                canonicalWorkoutEntry(
                                    exerciseId = PROJECTION_BUNDLED,
                                    exerciseName = "Bench Press",
                                    sets = canonicalJsonArray(canonicalWorkoutSet(repetitions = 1, weightKg = 1.0)),
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
        val entries = database.projectionQuery(
            "SELECT id FROM workout_exercises WHERE workout_id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        )
        assertEquals(listOf(CANONICAL_ENTRY_A1), entries.map { it["id"] })
        val sets = database.projectionQuery(
            "SELECT repetitions FROM workout_sets WHERE workout_exercise_id = ?",
            arrayOf(CANONICAL_ENTRY_A1),
        )
        assertEquals(listOf(1L), sets.map { it["repetitions"] })
        val workout = database.projectionQuery("SELECT name, server_revision FROM workouts WHERE id = ?", arrayOf(CANONICAL_WORKOUT_A)).single()
        assertEquals("Trimmed", workout["name"])
        assertEquals(2L, workout["server_revision"])
    }

    @Test
    fun absenceEvidenceCreatesNoFakeRoots() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                canonicalChange(CANONICAL_TYPE_WORKOUT, PROJECTION_WORKOUT_B, CANONICAL_OP_DELETE, 3L, null),
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO sync_canonical_roots
                (account_id, aggregate_type, aggregate_id, server_revision,
                 canonical_state, canonical_json, local_generation_watermark, cache_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, PROJECTION_ROUTINE_B, null,
                "unobserved", null, 4L, 0L,
            ),
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertTrue(
            database.projectionQuery("SELECT id FROM workouts WHERE id = ?", arrayOf(PROJECTION_WORKOUT_B)).isEmpty(),
        )
        assertTrue(
            database.projectionQuery("SELECT id FROM routines WHERE id = ?", arrayOf(PROJECTION_ROUTINE_B)).isEmpty(),
        )
        assertEquals(
            4L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, PROJECTION_ROUTINE_B)!!
                .localGenerationWatermark,
        )
        val deleted = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, PROJECTION_WORKOUT_B)!!
        assertEquals(3L, deleted.serverRevision)
        assertEquals("delete", deleted.canonicalState)
    }

    @Test
    fun cleanRetryReturnsUnchangedWithoutAdvancingSignal() = runTest {
        prepareSimpleGraph()
        val first = database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, first.status)
        assertEquals(1L, first.cacheEpoch)
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        val second = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(1L)
        assertEquals(ProjectionPublicationStatus.UNCHANGED, second.status)
        assertEquals(1L, second.cacheEpoch)
        assertEquals(1L, second.materializedEpoch)
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun invalidLeaseMessageCarriesNoDomainValues() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        lease.revoke()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionLeaseInvalidException")
        } catch (e: ProjectionLeaseInvalidException) {
            val message = e.message ?: ""
            assertTrue(!message.contains(CANONICAL_WORKOUT_A))
            assertTrue(!message.contains(CANONICAL_ACCOUNT))
        }
    }

    @Test
    fun graphExceptionMessageCarriesNoDomainValues() = runTest {
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
        try {
            database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
            fail("Expected ProjectionGraphException")
        } catch (e: ProjectionGraphException) {
            val message = e.message ?: ""
            assertTrue(!message.contains("99999999-9999-4999-8999-999999999999"))
            assertTrue(!message.contains(CANONICAL_ACCOUNT))
        }
        assertEquals(0, database.projectionDomainCount("workouts"))
    }

    @Test
    fun protectedPreservesVisibleIntentTombstonesAndSoleActive() = runTest {
        prepareSimpleGraph()
        database.projectionStore(barrier = projectionGrantBarrier()).publish(0L)
        insertRoutineAggregate(routineFixture(id = PROJECTION_ROUTINE_B, isDraft = true))
        insertWorkoutAggregate(
            workoutFixture(id = PROJECTION_WORKOUT_B).copy(accountId = CANONICAL_ACCOUNT, deletedAtMillis = 5L),
        )
        database.projectionInsertOutbox(
            mutationId = "20000000-0000-4000-8000-000000000096",
            aggregateId = CANONICAL_WORKOUT_A,
            state = "pending",
        )
        val before = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(1L)
        assertEquals(ProjectionPublicationStatus.PROTECTED, result.status)
        assertEquals(1L, result.cacheEpoch)
        assertEquals(1L, result.materializedEpoch)
        assertEquals(1, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
        assertEquals(
            "W",
            database.projectionQuery(
                "SELECT name FROM workouts WHERE id = ?",
                arrayOf(CANONICAL_WORKOUT_A),
            ).single()["name"],
        )
    }

    @Test
    fun protectedCorruptCanonicalBypass() = runTest {
        prepareSimpleGraph()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET canonical_json = ? " +
                "WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            arrayOf<Any?>("{corrupt", CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A),
        )
        database.projectionInsertOutbox(mutationId = "20000000-0000-4000-8000-000000000095")
        val lease = ProjectionTestLease()
        val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
        assertEquals(ProjectionPublicationStatus.PROTECTED, result.status)
        assertEquals(1, lease.closeCount)
        assertEquals(0, database.projectionDomainCount("workouts"))
        assertEquals(ProjectionCacheRow(true, 0L, true, 0L), database.projectionReadCache(CANONICAL_ACCOUNT))
    }

    @Test
    fun negativeRetainedAndDomainCountersThrowStorageException() = runTest {
        seedLookups()
        database.projectionPreparePublication(
            changes = canonicalJsonArray(
                canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, customA()),
                simpleWorkoutChange(),
            ),
        )
        insertExercises(
            exerciseFixture(id = CANONICAL_CUSTOM_A, name = "Fly", isUserCreated = true).copy(accountId = CANONICAL_ACCOUNT),
        )
        insertWorkoutAggregate(workoutFixture(id = CANONICAL_WORKOUT_A).copy(accountId = CANONICAL_ACCOUNT))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = -1 WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        )
        val beforeFirst = database.projectionDumpAll()
        val lease = ProjectionTestLease()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected CanonicalStorageException for negative domain generation")
        } catch (e: CanonicalStorageException) {
            assertTrue(!(e.message ?: "").contains(CANONICAL_WORKOUT_A))
        }
        assertEquals(1, lease.closeCount)
        assertEquals(beforeFirst, database.projectionDumpAll())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workouts SET local_generation = 0 WHERE id = ?",
            arrayOf(CANONICAL_WORKOUT_A),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET local_generation_watermark = -1 " +
                "WHERE account_id = ? AND aggregate_id = ?",
            arrayOf(CANONICAL_ACCOUNT, CANONICAL_WORKOUT_A),
        )
        val beforeSecond = database.projectionDumpAll()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected CanonicalStorageException for negative retained watermark")
        } catch (_: CanonicalStorageException) {
        }
        assertEquals(2, lease.closeCount)
        assertEquals(beforeSecond, database.projectionDumpAll())
        assertEquals(1, database.projectionDomainCount("workouts"))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_canonical_roots SET local_generation_watermark = 0 " +
                "WHERE account_id = ? AND aggregate_id = ?",
            arrayOf(CANONICAL_ACCOUNT, CANONICAL_WORKOUT_A),
        )
        val result = database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, result.status)
    }

    @Test
    fun cancellationAfterAcquireWritesKeepsAcquireEffects() = runTest {
        prepareSimpleGraph()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<ProjectionTestLease>()
        val barrier = ProjectionPublicationBarrier {
            entered.complete(Unit)
            database.projectionInsertOutbox(
                mutationId = "20000000-0000-4000-8000-000000000094",
                aggregateId = CANONICAL_WORKOUT_A,
            )
            gate.await()
        }
        val job = launch { database.projectionStore(barrier = barrier).publish(0L) }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
        assertEquals(0, database.projectionDomainCount("workouts"))
        assertEquals(ProjectionCacheRow(true, 0L, true, 0L), database.projectionReadCache(CANONICAL_ACCOUNT))
    }

    @Test
    fun leaseRevokedDuringDrainRollsBackAndClosesOnce() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease) {
            database.projectionInsertOutbox(
                mutationId = "20000000-0000-4000-8000-000000000093",
                aggregateId = CANONICAL_WORKOUT_A,
            )
            lease.revoke()
        }
        val canonicalBefore = database.projectionDump("sync_canonical_roots")
        val cacheBefore = database.projectionReadCache(CANONICAL_ACCOUNT)
        try {
            database.projectionStore(barrier = barrier).publish(0L)
            fail("Expected ProjectionLeaseInvalidException")
        } catch (_: ProjectionLeaseInvalidException) {
        }
        assertEquals(1, lease.closeCount)
        assertEquals(1, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
        assertEquals(0, database.projectionDomainCount("workouts"))
        assertEquals(canonicalBefore, database.projectionDump("sync_canonical_roots"))
        assertEquals(cacheBefore, database.projectionReadCache(CANONICAL_ACCOUNT))
    }

    @Test
    fun leaseCheckSqliteFailureKeepsIdentity() = runTest {
        prepareSimpleGraph()
        val injected = SQLiteException("injected lease check failure")
        var closes = 0
        val lease = object : ProjectionPublicationLease {
            override fun checkValid() {
                throw injected
            }
            override fun close() {
                closes++
            }
        }
        val before = database.projectionDumpAll()
        val caught = try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected injected lease check failure")
            null
        } catch (e: SQLiteException) {
            e
        }
        assertTrue(caught === injected)
        assertEquals(1, closes)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun alreadyCancelledCallerSkipsBarrierAcquisition() = runTest {
        prepareSimpleGraph()
        val lease = ProjectionTestLease()
        val barrier = projectionGrantBarrier(lease)
        val before = database.projectionDumpAll()
        val job = launch {
            cancel("injected pre-cancel")
            database.projectionStore(barrier = barrier).publish(0L)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(barrier.requests.isEmpty())
        assertEquals(0, lease.closeCount)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun lateCancellationBeforeCommitRollsBackAndClosesOnce() = runTest {
        prepareSimpleGraph()
        var closes = 0
        var postWriteReached = false
        lateinit var callerJob: Job
        val lease = object : ProjectionPublicationLease {
            override fun checkValid() {
                if (!postWriteReached) {
                    val installed = database.projectionQuery(
                        "SELECT id FROM workouts WHERE id = ?",
                        arrayOf(CANONICAL_WORKOUT_A),
                    ).isNotEmpty()
                    if (installed) {
                        postWriteReached = true
                        callerJob.cancel(CancellationException("injected caller cancellation"))
                    }
                }
            }
            override fun close() {
                closes++
            }
        }
        val before = database.projectionDumpAll()
        callerJob = launch { database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L) }
        callerJob.join()
        assertTrue(callerJob.isCancelled)
        assertTrue(postWriteReached)
        assertEquals(1, closes)
        assertEquals(before, database.projectionDumpAll())
    }

    @Test
    fun finalRevocationBeforeCommitRollsBackAndClosesOnce() = runTest {
        prepareSimpleGraph()
        var closes = 0
        var postWriteReached = false
        var revoked = false
        val lease = object : ProjectionPublicationLease {
            override fun checkValid() {
                if (revoked) throw ProjectionLeaseInvalidException()
                if (!postWriteReached) {
                    val installed = database.projectionQuery(
                        "SELECT id FROM workouts WHERE id = ?",
                        arrayOf(CANONICAL_WORKOUT_A),
                    ).isNotEmpty()
                    if (installed) {
                        postWriteReached = true
                        revoked = true
                        throw ProjectionLeaseInvalidException()
                    }
                }
            }
            override fun close() {
                closes++
            }
        }
        val before = database.projectionDumpAll()
        try {
            database.projectionStore(barrier = projectionGrantBarrier(lease)).publish(0L)
            fail("Expected ProjectionLeaseInvalidException")
        } catch (_: ProjectionLeaseInvalidException) {
        }
        assertTrue(postWriteReached)
        assertEquals(1, closes)
        assertEquals(before, database.projectionDumpAll())
    }
}
