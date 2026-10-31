package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.EquipmentEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.MuscleGroupEntity
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseEntity
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.store.CanonicalConflictException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.CanonicalTransaction
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.StaleCanonicalEpochException
import com.dragosfotache.gymtracka.data.sync.store.StaleSessionException
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * canonical-batch acceptance. Real Room, no mocked DAOs.
 */
class CanonicalBatchTest : GymDatabaseTest() {

    private companion object {
        const val STARTED_MILLIS = 1_790_258_400_000L
        const val MUTATION_E1 = "10000000-0000-4000-8000-000000000001"
        const val MUTATION_E2 = "10000000-0000-4000-8000-000000000002"
        const val MUTATION_E3 = "10000000-0000-4000-8000-000000000003"
        const val OUTBOX_REJECTED = "20000000-0000-4000-8000-000000000001"
        const val OUTBOX_CORRUPT = "20000000-0000-4000-8000-000000000002"
        const val OUTBOX_UNSUPPORTED = "20000000-0000-4000-8000-000000000003"
    }

    private fun validWorkoutUpsertBatch(
        id: String = CANONICAL_WORKOUT_A,
        revision: Long = 1L,
        record: JSONObject = canonicalWorkoutRecord(id = id, revision = revision),
        batchId: String = CANONICAL_BATCH_1,
        cursor: String = CANONICAL_CURSOR_1,
    ): SyncBatchModel =
        canonicalDecodeBatch(
            canonicalPull(
                batchId = batchId,
                cursor = cursor,
                changes = canonicalJsonArray(canonicalChange(CANONICAL_TYPE_WORKOUT, id, CANONICAL_OP_UPSERT, revision, record)),
            ),
        )

    private suspend fun assertInvalidPullRejected(json: String) {
        database.canonicalSeedDevice()
        try {
            val batch = canonicalDecodeBatch(json)
            canonicalStore(database).applyBatch(batch, 0L)
            fail("Expected SyncModelException")
        } catch (_: SyncModelException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    private suspend fun assertInvalidBatchRejected(batch: SyncBatchModel) {
        database.canonicalSeedDevice()
        try {
            canonicalStore(database).applyBatch(batch, 0L)
            fail("Expected SyncModelException")
        } catch (_: SyncModelException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    private suspend fun assertRetainedTargetFailsClosed(
        seedCorrupt: () -> Unit,
        batch: SyncBatchModel,
        verifyPreserved: suspend () -> Unit,
    ) {
        database.canonicalSeedDevice()
        seedCorrupt()
        try {
            canonicalStore(database).applyBatch(batch, 0L)
            fail("Expected CanonicalStorageException")
        } catch (_: CanonicalStorageException) {
        }
        verifyPreserved()
        assertEquals(1, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
        assertEquals(0, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
    }

    private fun snapshotTable(table: String, orderBy: String): List<String> {
        database.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY $orderBy").use { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.moveToNext()) {
                rows.add(
                    (0 until cursor.columnCount).joinToString("|") { i ->
                        when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i).toString()
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i).toString()
                            else -> cursor.getString(i) ?: "null"
                        }
                    },
                )
            }
            return rows
        }
    }

    private fun snapshotDomain(): Map<String, List<String>> =
        mapOf(
            "workouts" to snapshotTable("workouts", "id"),
            "workout_exercises" to snapshotTable("workout_exercises", "id"),
            "workout_sets" to snapshotTable("workout_sets", "id"),
            "routines" to snapshotTable("routines", "id"),
            "routine_exercises" to snapshotTable("routine_exercises", "id"),
            "routine_sets" to snapshotTable("routine_sets", "id"),
            "exercises" to snapshotTable("exercises", "id"),
            "exercise_muscle_groups" to snapshotTable("exercise_muscle_groups", "exercise_id, muscle_group_id"),
            "exercise_equipment" to snapshotTable("exercise_equipment", "exercise_id, equipment_id"),
            "muscle_groups" to snapshotTable("muscle_groups", "id"),
            "equipment" to snapshotTable("equipment", "id"),
        )

    private suspend fun seedDomainWorkout(
        id: String,
        account: String? = CANONICAL_ACCOUNT,
        generation: Long = 0L,
        name: String = "W",
        serverRevision: Long? = null,
    ) {
        database.workoutDao().insertWorkout(
            WorkoutEntity(
                id = id,
                name = name,
                startedAtMillis = 1_000L,
                accountId = account,
                serverRevision = serverRevision,
                localGeneration = generation,
            ),
        )
    }

    // Acceptance 1: whole complete observations.

    @Test
    fun wholeActiveWorkoutObservationPersistsCompleteAggregate() = runTest {
        database.canonicalSeedDevice()
        database.syncDao().upsertSyncState(SyncStateEntity(CANONICAL_ACCOUNT, "cursor-9", 12345L))
        val setsEntry1 = canonicalJsonArray(
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
        )
        val record = canonicalWorkoutRecord(
            id = CANONICAL_WORKOUT_A,
            name = "W",
            status = "active",
            startedAt = CANONICAL_STARTED_AT,
            finishedAt = null,
            revision = 1L,
            exercises = canonicalJsonArray(
                canonicalWorkoutEntry(
                    id = CANONICAL_ENTRY_A1,
                    exerciseId = CANONICAL_EXERCISE_REF,
                    exerciseName = "Press",
                    position = 0,
                    notes = "Keep",
                    restDurationSeconds = 90,
                    sets = setsEntry1,
                ),
                canonicalWorkoutEntry(
                    id = CANONICAL_ENTRY_A2,
                    exerciseId = CANONICAL_EXERCISE_REF_2,
                    exerciseName = "Row",
                    position = 1,
                    notes = "",
                    restDurationSeconds = 30,
                    sets = canonicalJsonArray(
                        canonicalWorkoutSet(
                            id = CANONICAL_SET_A3,
                            position = 0,
                            repetitions = null,
                            weightKg = null,
                            isCompleted = false,
                            restSeconds = null,
                        ),
                    ),
                ),
            ),
            totalVolumeKg = 800.0,
        )
        val result = canonicalStore(database).applyBatch(validWorkoutUpsertBatch(record = record), 0L)
        assertEquals(1, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)

        val root = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(1L, root.serverRevision)
        assertEquals("upsert", root.canonicalState)
        assertEquals(0L, root.localGenerationWatermark)
        assertEquals(0L, root.cacheEpoch)

        val stored = JSONObject(root.canonicalJson!!)
        assertEquals("W", stored.getString("name"))
        assertEquals("active", stored.getString("status"))
        assertEquals(CANONICAL_STARTED_AT, stored.getString("startedAt"))
        assertTrue(stored.isNull("finishedAt"))
        assertEquals(1L, stored.getLong("revision"))
        val exercises = stored.getJSONArray("exercises")
        assertEquals(2, exercises.length())
        val first = exercises.getJSONObject(0)
        assertEquals(CANONICAL_ENTRY_A1, first.getString("id"))
        assertEquals(0, first.getInt("position"))
        assertEquals("Keep", first.getString("notes"))
        assertEquals(90, first.getInt("restDurationSeconds"))
        val firstSets = first.getJSONArray("sets")
        assertEquals(2, firstSets.length())
        assertEquals(8, firstSets.getJSONObject(0).getInt("repetitions"))
        assertEquals(100.0, firstSets.getJSONObject(0).getDouble("weightKg"), 0.0)
        assertEquals(87, firstSets.getJSONObject(0).getInt("restSeconds"))
        assertEquals(0, firstSets.getJSONObject(1).getInt("repetitions"))
        assertEquals(0.0, firstSets.getJSONObject(1).getDouble("weightKg"), 0.0)
        assertTrue(firstSets.getJSONObject(1).isNull("restSeconds"))
        val second = exercises.getJSONObject(1)
        assertEquals(CANONICAL_ENTRY_A2, second.getString("id"))
        assertEquals(1, second.getInt("position"))
        val secondSets = second.getJSONArray("sets")
        assertTrue(secondSets.getJSONObject(0).isNull("repetitions"))
        assertTrue(secondSets.getJSONObject(0).isNull("weightKg"))

        val cache = database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)!!
        assertEquals(false, cache.baselineComplete)
        assertEquals(0L, cache.cacheEpoch)
        assertEquals(true, cache.projectionDirty)
        assertEquals(0L, cache.materializedEpoch)

        assertEquals(0, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
        assertNull(database.workoutDao().getWorkout(CANONICAL_WORKOUT_A))
        val syncAfter = database.syncDao().getSyncState(CANONICAL_ACCOUNT)!!
        assertEquals("cursor-9", syncAfter.cursor)
        assertEquals(12345L, syncAfter.lastSuccessMillis)
    }

    @Test
    fun finishedWorkoutReversedBusinessTimePreserved() = runTest {
        database.canonicalSeedDevice()
        val record = canonicalWorkoutRecord(
            id = CANONICAL_WORKOUT_B,
            name = "Evening",
            status = "finished",
            startedAt = "2026-09-24T15:02:12.456Z",
            finishedAt = CANONICAL_EARLIER_AT,
            revision = 3L,
            exercises = canonicalJsonArray(
                canonicalWorkoutEntry(
                    sets = canonicalJsonArray(
                        canonicalWorkoutSet(isCompleted = true, repetitions = 5, weightKg = 60.0, restSeconds = 90),
                    ),
                ),
            ),
            totalVolumeKg = 300.0,
        )
        val result = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(id = CANONICAL_WORKOUT_B, revision = 3L, record = record),
            0L,
        )
        assertEquals(1, result.appliedRoots)
        val stored = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!.canonicalJson!!,
        )
        assertEquals("finished", stored.getString("status"))
        assertEquals("2026-09-24T15:02:12.456Z", stored.getString("startedAt"))
        assertEquals(CANONICAL_EARLIER_AT, stored.getString("finishedAt"))
        assertTrue(stored.getString("finishedAt") < stored.getString("startedAt"))
    }

    @Test
    fun draftBlankEmptyAndSavedRoutineObservations() = runTest {
        database.canonicalSeedDevice()
        val batch = canonicalDecodeBatch(
            canonicalPull(
                changes = canonicalJsonArray(
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
                                    sets = canonicalJsonArray(canonicalRoutineSet(repetitions = 10, weightKg = 40.0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = canonicalStore(database).applyBatch(batch, 0L)
        assertEquals(2, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)

        val draft = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A)!!.canonicalJson!!,
        )
        assertEquals("", draft.getString("name"))
        assertEquals(true, draft.getBoolean("isDraft"))
        assertEquals(0, draft.getJSONArray("exercises").length())

        val saved = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B)!!.canonicalJson!!,
        )
        assertEquals("Legs", saved.getString("name"))
        assertEquals(false, saved.getBoolean("isDraft"))
        val entry = saved.getJSONArray("exercises").getJSONObject(0)
        assertEquals(CANONICAL_ENTRY_A1, entry.getString("id"))
        assertEquals("Press", entry.getString("exerciseName"))
        assertEquals(10, entry.getJSONArray("sets").getJSONObject(0).getInt("repetitions"))
        assertEquals(40.0, entry.getJSONArray("sets").getJSONObject(0).getDouble("weightKg"), 0.0)
    }

    @Test
    fun customObservationMetadataNullVersusEmpty() = runTest {
        database.canonicalSeedDevice()
        val full = canonicalCustomRecord(
            id = CANONICAL_CUSTOM_A,
            name = "Custom",
            revision = 1L,
            category = "strength",
            difficultyLevel = "intermediate",
            forceType = "push",
            mechanics = "compound",
            instructions = canonicalJsonArray().let {
                it.put("Lower with control")
                it.put("Press to lockout")
                it
            },
        )
        val nulls = canonicalCustomRecord(id = CANONICAL_CUSTOM_B, revision = 1L)
        val first = canonicalStore(database).applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L, full),
                        canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B, CANONICAL_OP_UPSERT, 1L, nulls),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(2, first.appliedRoots)

        val storedFull = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson!!,
        )
        assertEquals("strength", storedFull.getString("category"))
        assertEquals("intermediate", storedFull.getString("difficultyLevel"))
        assertEquals("push", storedFull.getString("forceType"))
        assertEquals("compound", storedFull.getString("mechanics"))
        assertEquals(2, storedFull.getJSONArray("instructions").length())
        assertEquals("reps", storedFull.getString("type"))
        assertEquals(true, storedFull.getBoolean("isUserCreated"))

        val storedNulls = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B)!!.canonicalJson!!,
        )
        assertTrue(storedNulls.isNull("category"))
        assertTrue(storedNulls.isNull("instructions"))

        val emptied = canonicalCustomRecord(
            id = CANONICAL_CUSTOM_B,
            revision = 2L,
            instructions = JSONArray(),
        )
        val second = canonicalStore(database).applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B, CANONICAL_OP_UPSERT, 2L, emptied),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, second.appliedRoots)
        val storedEmpty = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B)!!.canonicalJson!!,
        )
        assertEquals(2L, storedEmpty.getLong("revision"))
        assertEquals(0, storedEmpty.getJSONArray("instructions").length())
    }

    // Acceptance 2: revisions and equal-revision arbitration.

    @Test
    fun unknownRootDeletePersistsEvidenceAndReplayIsNoop() = runTest {
        database.canonicalSeedDevice()
        val delete = canonicalDecodeBatch(
            canonicalPull(
                changes = canonicalJsonArray(
                    canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 3L, null),
                ),
            ),
        )
        val result = canonicalStore(database).applyBatch(delete, 0L)
        assertEquals(1, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        val root = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(3L, root.serverRevision)
        assertEquals("delete", root.canonicalState)
        assertNull(root.canonicalJson)
        assertEquals(0L, root.localGenerationWatermark)
        assertNull(database.workoutDao().getWorkout(CANONICAL_WORKOUT_A))

        val replay = canonicalStore(database).applyBatch(delete, 0L)
        assertEquals(0, replay.appliedRoots)
        assertEquals(1, replay.ignoredRoots)
        val retained = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(3L, retained.serverRevision)
        assertEquals("delete", retained.canonicalState)
    }

    @Test
    fun olderUpsertAndDeleteIgnoredAfterTombstone() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 3L, null),
                    ),
                ),
            ),
            0L,
        )
        val olderUpsert = store.applyBatch(validWorkoutUpsertBatch(revision = 2L), 0L)
        assertEquals(0, olderUpsert.appliedRoots)
        assertEquals(1, olderUpsert.ignoredRoots)
        val olderDelete = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 1L, null),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(0, olderDelete.appliedRoots)
        assertEquals(1, olderDelete.ignoredRoots)
        val root = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(3L, root.serverRevision)
        assertEquals("delete", root.canonicalState)
        assertNull(root.canonicalJson)
    }

    @Test
    fun newerWinnerThenReplayedOldBatchIgnored() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val oldBatch = validWorkoutUpsertBatch(revision = 4L)
        store.applyBatch(oldBatch, 0L)
        val newer = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 5L,
                record = canonicalWorkoutRecord(revision = 5L, name = "Newer"),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            0L,
        )
        assertEquals(1, newer.appliedRoots)
        assertEquals(
            "Newer",
            JSONObject(
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
            ).getString("name"),
        )
        val replay = store.applyBatch(oldBatch, 0L)
        assertEquals(0, replay.appliedRoots)
        assertEquals(1, replay.ignoredRoots)
        assertEquals(
            "Newer",
            JSONObject(
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
            ).getString("name"),
        )
    }

    @Test
    fun equalRevisionWorkoutLabelOnlyNoopKeepsStoredJson() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 5L,
                record = canonicalWorkoutRecord(
                    revision = 5L,
                    exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseName = "Press")),
                ),
            ),
            0L,
        )
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        val result = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 5L,
                record = canonicalWorkoutRecord(
                    revision = 5L,
                    exercises = canonicalJsonArray(canonicalWorkoutEntry(exerciseName = "Overhead Press")),
                ),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            0L,
        )
        assertEquals(0, result.appliedRoots)
        assertEquals(1, result.ignoredRoots)
        val after = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(before, after.canonicalJson)
        assertEquals(
            "Press",
            JSONObject(after.canonicalJson!!).getJSONArray("exercises").getJSONObject(0).getString("exerciseName"),
        )
    }

    @Test
    fun equalRevisionDerivedVolumeDifferenceIsNoop() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val represented = canonicalJsonArray(
            canonicalWorkoutEntry(
                exerciseName = "Press",
                notes = "Keep",
                sets = canonicalJsonArray(
                    canonicalWorkoutSet(repetitions = 8, weightKg = 100.0, isCompleted = true),
                ),
            ),
        )
        store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 5L,
                record = canonicalWorkoutRecord(revision = 5L, name = "W", exercises = represented),
            ),
            0L,
        )
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        val result = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 5L,
                record = canonicalWorkoutRecord(revision = 5L, name = "W", exercises = represented, totalVolumeKg = 800.0),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            0L,
        )
        assertEquals(0, result.appliedRoots)
        assertEquals(1, result.ignoredRoots)
        val after = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(before, after.canonicalJson)
        val stored = JSONObject(after.canonicalJson!!)
        assertEquals("W", stored.getString("name"))
        assertEquals("active", stored.getString("status"))
        assertEquals(5L, stored.getLong("revision"))
        val entry = stored.getJSONArray("exercises").getJSONObject(0)
        assertEquals(CANONICAL_ENTRY_A1, entry.getString("id"))
        assertEquals("Press", entry.getString("exerciseName"))
        assertEquals("Keep", entry.getString("notes"))
        val set = entry.getJSONArray("sets").getJSONObject(0)
        assertEquals(8, set.getInt("repetitions"))
        assertEquals(100.0, set.getDouble("weightKg"), 0.0)
        assertEquals(true, set.getBoolean("isCompleted"))
    }

    @Test
    fun equalRevisionWorkoutConflictRollsBackWholeBatch() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            validWorkoutUpsertBatch(revision = 5L, record = canonicalWorkoutRecord(revision = 5L, name = "W")),
            0L,
        )
        val conflicting = canonicalDecodeBatch(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_B,
                        CANONICAL_OP_UPSERT,
                        1L,
                        canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
                    ),
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_A,
                        CANONICAL_OP_UPSERT,
                        5L,
                        canonicalWorkoutRecord(revision = 5L, name = "Changed"),
                    ),
                ),
            ),
        )
        try {
            store.applyBatch(conflicting, 0L)
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
        assertEquals(
            "W",
            JSONObject(
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
            ).getString("name"),
        )
    }

    @Test
    fun equalRevisionOperationDisagreementRejects() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(validWorkoutUpsertBatch(revision = 5L), 0L)
        try {
            store.applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        batchId = CANONICAL_BATCH_2,
                        cursor = CANONICAL_CURSOR_2,
                        changes = canonicalJsonArray(
                            canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 5L, null),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        val root = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(5L, root.serverRevision)
        assertEquals("upsert", root.canonicalState)
        assertNotNull(root.canonicalJson)
    }

    @Test
    fun equalRevisionRoutineAgreementAndConflict() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val base = canonicalRoutineRecord(
            revision = 2L,
            exercises = canonicalJsonArray(
                canonicalRoutineEntry(
                    exerciseName = "Press",
                    sets = canonicalJsonArray(canonicalRoutineSet(weightKg = 50.0)),
                ),
            ),
        )
        store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(canonicalChange(CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A, CANONICAL_OP_UPSERT, 2L, base)),
                ),
            ),
            0L,
        )
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A)!!.canonicalJson!!
        val labelOnly = store.applyBatch(
            canonicalDecodeBatch(
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
                                revision = 2L,
                                exercises = canonicalJsonArray(
                                    canonicalRoutineEntry(
                                        exerciseName = "Flat Press",
                                        sets = canonicalJsonArray(canonicalRoutineSet(weightKg = 50.0)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(0, labelOnly.appliedRoots)
        assertEquals(1, labelOnly.ignoredRoots)
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A)!!.canonicalJson,
        )

        try {
            store.applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        batchId = "33333333-3333-3333-3333-333333333333",
                        cursor = "cursor-3",
                        changes = canonicalJsonArray(
                            canonicalChange(
                                CANONICAL_TYPE_ROUTINE,
                                CANONICAL_ROUTINE_A,
                                CANONICAL_OP_UPSERT,
                                2L,
                                canonicalRoutineRecord(
                                    revision = 2L,
                                    exercises = canonicalJsonArray(
                                        canonicalRoutineEntry(
                                            exerciseName = "Press",
                                            sets = canonicalJsonArray(canonicalRoutineSet(weightKg = 60.0)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A)!!.canonicalJson,
        )
    }

    @Test
    fun equalRevisionCustomAgreementAndConflict() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM,
                            CANONICAL_CUSTOM_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalCustomRecord(name = "Custom", revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson!!
        val agreement = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM,
                            CANONICAL_CUSTOM_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalCustomRecord(name = "Custom", revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(0, agreement.appliedRoots)
        assertEquals(1, agreement.ignoredRoots)
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson,
        )

        try {
            store.applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        batchId = "33333333-3333-3333-3333-333333333333",
                        cursor = "cursor-3",
                        changes = canonicalJsonArray(
                            canonicalChange(
                                CANONICAL_TYPE_CUSTOM,
                                CANONICAL_CUSTOM_A,
                                CANONICAL_OP_UPSERT,
                                1L,
                                canonicalCustomRecord(name = "Renamed", revision = 1L),
                            ),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson,
        )
    }

    // Acceptance 3: atomic composition and rollback.

    @Test
    fun atomicDeleteOldUpsertNewCommitsTogether() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(validWorkoutUpsertBatch(revision = 4L), 0L)
        val result = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 5L, null),
                        canonicalChange(
                            CANONICAL_TYPE_WORKOUT,
                            CANONICAL_WORKOUT_B,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(2, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        val deleted = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(5L, deleted.serverRevision)
        assertEquals("delete", deleted.canonicalState)
        assertNull(deleted.canonicalJson)
        val added = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!
        assertEquals(1L, added.serverRevision)
        assertEquals("upsert", added.canonicalState)
    }

    @Test
    fun mixedObsoleteAndNewChanges() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(validWorkoutUpsertBatch(revision = 5L), 0L)
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        val result = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_WORKOUT,
                            CANONICAL_WORKOUT_A,
                            CANONICAL_OP_UPSERT,
                            3L,
                            canonicalWorkoutRecord(revision = 3L),
                        ),
                        canonicalChange(
                            CANONICAL_TYPE_WORKOUT,
                            CANONICAL_WORKOUT_C,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalWorkoutRecord(id = CANONICAL_WORKOUT_C, revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, result.appliedRoots)
        assertEquals(1, result.ignoredRoots)
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
        )
        assertNotNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C))
    }

    @Test
    fun injectedLaterWriteFailureRollsBack() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(validWorkoutUpsertBatch(revision = 1L), 0L)
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_canonical_insert BEFORE INSERT ON sync_canonical_roots " +
                "WHEN NEW.aggregate_id = '$CANONICAL_WORKOUT_B' " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )
        try {
            try {
                store.applyBatch(
                    canonicalDecodeBatch(
                        canonicalPull(
                            batchId = CANONICAL_BATCH_2,
                            cursor = CANONICAL_CURSOR_2,
                            changes = canonicalJsonArray(
                                canonicalChange(
                                    CANONICAL_TYPE_WORKOUT,
                                    CANONICAL_WORKOUT_A,
                                    CANONICAL_OP_UPSERT,
                                    2L,
                                    canonicalWorkoutRecord(revision = 2L, name = "Edited"),
                                ),
                                canonicalChange(
                                    CANONICAL_TYPE_WORKOUT,
                                    CANONICAL_WORKOUT_B,
                                    CANONICAL_OP_UPSERT,
                                    1L,
                                    canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
                                ),
                            ),
                        ),
                    ),
                    0L,
                )
                fail("Expected injected write failure")
            } catch (e: RuntimeException) {
                if (e is CancellationException) throw e
            }
            assertEquals(
                before,
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
            )
            assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
            assertEquals(1, database.canonicalCanonicalCount())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_canonical_insert")
        }
    }

    @Test
    fun composedTransactionCallbackFailureRollsBack() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val batch = validWorkoutUpsertBatch()
        val injected = IllegalStateException("injected callback failure")
        val caught = try {
            store.withFencedTransaction<Nothing>(0L) { transaction ->
                val applied = transaction.applyBatch(batch)
                assertEquals(1, applied.appliedRoots)
                assertEquals(0, applied.ignoredRoots)
                throw injected
            }
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(caught === injected)
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertNull(database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT))
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    @Test
    fun composedTransactionCancellationRollsBack() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val batch = validWorkoutUpsertBatch()
        val effectsWritten = CompletableDeferred<Unit>()
        val applyJob = launch {
            store.withFencedTransaction(0L) { transaction ->
                transaction.applyBatch(batch)
                effectsWritten.complete(Unit)
                awaitCancellation()
            }
        }
        effectsWritten.await()
        applyJob.cancel()
        applyJob.join()
        assertTrue(applyJob.isCancelled)
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertNull(database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT))
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    @Test
    fun multiBatchComposedConflictRollsBackFirstBatch() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(validWorkoutUpsertBatch(revision = 5L), 0L)
        val first = validWorkoutUpsertBatch(
            id = CANONICAL_WORKOUT_B,
            revision = 1L,
            record = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
            batchId = CANONICAL_BATCH_2,
            cursor = CANONICAL_CURSOR_2,
        )
        val conflicting = canonicalDecodeBatch(
            canonicalPull(
                batchId = "33333333-3333-3333-3333-333333333333",
                cursor = "cursor-3",
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_A,
                        CANONICAL_OP_UPSERT,
                        5L,
                        canonicalWorkoutRecord(revision = 5L, name = "Changed"),
                    ),
                ),
            ),
        )
        try {
            store.withFencedTransaction(0L) { transaction ->
                transaction.applyBatch(first)
                transaction.applyBatch(conflicting)
            }
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
        assertEquals(
            5L,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.serverRevision,
        )
    }

    @Test
    fun closedTransactionUseThrows() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        var leaked: CanonicalTransaction? = null
        store.withFencedTransaction(0L) { transaction ->
            leaked = transaction
            transaction.applyBatch(validWorkoutUpsertBatch())
        }
        assertNotNull(leaked)
        try {
            leaked!!.applyBatch(
                validWorkoutUpsertBatch(
                    id = CANONICAL_WORKOUT_B,
                    record = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B),
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                ),
            )
            fail("Expected IllegalStateException for closed transaction")
        } catch (_: IllegalStateException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
    }

    // Acceptance 4: fences, epochs and bookkeeping.

    @Test
    fun staleAccountFailsWithoutWrites() = runTest {
        database.canonicalSeedDevice(account = CANONICAL_OTHER_ACCOUNT)
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), 0L)
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    @Test
    fun staleGenerationFailsWithoutWrites() = runTest {
        database.canonicalSeedDevice()
        try {
            canonicalStore(database, RepositorySession(CANONICAL_ACCOUNT, CANONICAL_GENERATION - 1))
                .applyBatch(validWorkoutUpsertBatch(), 0L)
            fail("Expected StaleSessionException")
        } catch (_: StaleSessionException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    @Test
    fun signedOutZeroGenerationAndMissingDeviceFail() = runTest {
        try {
            CanonicalStore(database, RepositorySession.SIGNED_OUT)
            fail("Expected IllegalArgumentException for signed-out session")
        } catch (_: IllegalArgumentException) {
        }
        try {
            CanonicalStore(database, RepositorySession(CANONICAL_ACCOUNT, 0L))
            fail("Expected IllegalArgumentException for zero generation")
        } catch (_: IllegalArgumentException) {
        }
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), 0L)
            fail("Expected StaleSessionException for missing device state")
        } catch (_: StaleSessionException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
    }

    @Test
    fun wrongAndNegativeCacheEpochFail() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, false, 5L, false, 0L)
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), 4L)
            fail("Expected StaleCanonicalEpochException")
        } catch (_: StaleCanonicalEpochException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(false, database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)?.projectionDirty)
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), -1L)
            fail("Expected IllegalArgumentException for negative epoch")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
    }

    @Test
    fun selectedWritesPreserveBaselineEpochMaterializedAndDirty() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, true, 3L, false, 2L)
        val result = canonicalStore(database).applyBatch(validWorkoutUpsertBatch(revision = 5L), 3L)
        assertEquals(1, result.appliedRoots)
        assertEquals(3L, database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.cacheEpoch)
        val cache = database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)!!
        assertEquals(true, cache.baselineComplete)
        assertEquals(3L, cache.cacheEpoch)
        assertEquals(true, cache.projectionDirty)
        assertEquals(2L, cache.materializedEpoch)

        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        val noop = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(
                revision = 4L,
                record = canonicalWorkoutRecord(revision = 4L),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            3L,
        )
        assertEquals(0, noop.appliedRoots)
        assertEquals(1, noop.ignoredRoots)
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
        )
        val after = database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)!!
        assertEquals(true, after.baselineComplete)
        assertEquals(3L, after.cacheEpoch)
        assertEquals(true, after.projectionDirty)
        assertEquals(2L, after.materializedEpoch)
    }

    @Test
    fun noopBatchWritesNothingAndCreatesNoBookkeeping() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT,
            CANONICAL_TYPE_WORKOUT,
            CANONICAL_WORKOUT_A,
            5L,
            "upsert",
            canonicalWorkoutRecord(revision = 5L).toString(),
            0L,
            0L,
        )
        val result = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(
                revision = 4L,
                record = canonicalWorkoutRecord(revision = 4L),
            ),
            0L,
        )
        assertEquals(0, result.appliedRoots)
        assertEquals(1, result.ignoredRoots)
        assertNull(database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT))
        assertEquals(0, database.canonicalCacheCount())
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.serverRevision,
        )
    }

    @Test
    fun restartRetainsCanonicalAndCache() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "canonical-canonical-file-backed.db"
        context.deleteDatabase(name)
        try {
            val fileDatabase = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                fileDatabase.canonicalSeedDevice()
                val result = canonicalStore(fileDatabase).applyBatch(validWorkoutUpsertBatch(revision = 2L), 0L)
                assertEquals(1, result.appliedRoots)
            } finally {
                fileDatabase.close()
            }
            val reopened = Room.databaseBuilder(context, GymDatabase::class.java, name).build()
            try {
                val root = reopened.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
                assertEquals(2L, root.serverRevision)
                assertEquals("upsert", root.canonicalState)
                assertEquals(0L, root.localGenerationWatermark)
                assertEquals(2L, JSONObject(root.canonicalJson!!).getLong("revision"))
                val cache = reopened.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)!!
                assertEquals(true, cache.projectionDirty)
                assertEquals(false, cache.baselineComplete)
                assertEquals(0L, cache.cacheEpoch)
                assertEquals(0L, cache.materializedEpoch)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    // Acceptance 5: watermarks and local intent preservation.

    @Test
    fun watermarkExactAcrossNewerObservationAndDeleteOutboxUnchanged() = runTest {
        database.canonicalSeedDevice()
        seedDomainWorkout(CANONICAL_WORKOUT_A)
        val ids = mutableListOf(MUTATION_E1, MUTATION_E2, MUTATION_E3)
        val syncStore = SyncStore(database, RepositorySession(CANONICAL_ACCOUNT, CANONICAL_GENERATION)) { ids.removeFirst() }
        val outcome = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, CANONICAL_WORKOUT_A, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(CANONICAL_WORKOUT_A, "W", STARTED_MILLIS) }
        }!!
        assertEquals(1L, outcome.request?.envelope?.localGeneration)
        val before = database.syncDao().getOutboxMutation(MUTATION_E1, CANONICAL_ACCOUNT)!!

        val store = canonicalStore(database)
        val upserted = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 9L,
                record = canonicalWorkoutRecord(revision = 9L, name = "Server"),
            ),
            0L,
        )
        assertEquals(1, upserted.appliedRoots)
        assertEquals(
            1L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.localGenerationWatermark,
        )
        val afterUpsert = database.syncDao().getOutboxMutation(MUTATION_E1, CANONICAL_ACCOUNT)!!
        assertEquals(before.payloadJson, afterUpsert.payloadJson)
        assertEquals(before.state, afterUpsert.state)
        assertEquals(before.localGeneration, afterUpsert.localGeneration)

        val deleted = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_DELETE, 10L, null),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, deleted.appliedRoots)
        assertEquals(
            1L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.localGenerationWatermark,
        )
        val afterDelete = database.syncDao().getOutboxMutation(MUTATION_E1, CANONICAL_ACCOUNT)!!
        assertEquals(before.payloadJson, afterDelete.payloadJson)
        assertEquals(before.state, afterDelete.state)
        assertEquals(1, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
        assertEquals(1L, database.syncCaptureDao().getWorkoutMetadata(CANONICAL_WORKOUT_A)?.localGeneration)
        assertEquals("W", database.workoutDao().getWorkout(CANONICAL_WORKOUT_A)?.name)
    }

    @Test
    fun frozenRejectedCorruptUnsupportedOutboxUnchanged() = runTest {
        database.canonicalSeedDevice()
        seedDomainWorkout(CANONICAL_WORKOUT_A)
        val ids = mutableListOf(MUTATION_E1, MUTATION_E2)
        val syncStore = SyncStore(database, RepositorySession(CANONICAL_ACCOUNT, CANONICAL_GENERATION)) { ids.removeFirst() }
        syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, CANONICAL_WORKOUT_A, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(CANONICAL_WORKOUT_A, "W", STARTED_MILLIS) }
        }
        database.syncDao().freezePendingMutation(MUTATION_E1, CANONICAL_ACCOUNT)
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = OUTBOX_REJECTED,
                accountId = CANONICAL_ACCOUNT,
                aggregateType = CANONICAL_TYPE_WORKOUT,
                aggregateId = CANONICAL_WORKOUT_A,
                mutationType = "workout.upsert",
                localGeneration = 2L,
                payloadVersion = 1,
                payloadJson = "{}",
                state = "rejected",
                attemptCount = 3,
                retryAfterMillis = 60000L,
                errorCode = "SERVER_REJECTED",
                errorMessage = "bad envelope",
            ),
        )
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = OUTBOX_CORRUPT,
                accountId = CANONICAL_ACCOUNT,
                aggregateType = CANONICAL_TYPE_WORKOUT,
                aggregateId = CANONICAL_WORKOUT_A,
                mutationType = "workout.upsert",
                localGeneration = 3L,
                payloadVersion = 999,
                payloadJson = "{}",
                state = "corrupt",
            ),
        )
        database.syncDao().insertOutbox(
            SyncOutboxEntity(
                mutationId = OUTBOX_UNSUPPORTED,
                accountId = CANONICAL_ACCOUNT,
                aggregateType = CANONICAL_TYPE_WORKOUT,
                aggregateId = CANONICAL_WORKOUT_A,
                mutationType = "workout.upsert",
                localGeneration = 4L,
                payloadVersion = 1,
                payloadJson = "{invalid",
                state = "unsupported",
            ),
        )
        val outboxBefore = database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT)
        assertEquals(4, outboxBefore.size)

        val result = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(revision = 9L, record = canonicalWorkoutRecord(revision = 9L)),
            0L,
        )
        assertEquals(1, result.appliedRoots)
        assertEquals(outboxBefore, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT))
        assertEquals("frozen", database.syncDao().getOutboxMutation(MUTATION_E1, CANONICAL_ACCOUNT)!!.state)
    }

    @Test
    fun subsequentLocalCaptureAdvancesBeyondWatermark() = runTest {
        database.canonicalSeedDevice()
        seedDomainWorkout(CANONICAL_WORKOUT_A)
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, null, "unobserved", null, 4L, 0L,
        )
        val ids = mutableListOf(MUTATION_E1, MUTATION_E2)
        val syncStore = SyncStore(database, RepositorySession(CANONICAL_ACCOUNT, CANONICAL_GENERATION)) { ids.removeFirst() }
        val first = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, CANONICAL_WORKOUT_A, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(CANONICAL_WORKOUT_A, "W", STARTED_MILLIS) }
        }!!
        assertEquals(5L, first.request?.envelope?.localGeneration)
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.localGenerationWatermark,
        )

        val applied = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(revision = 9L, record = canonicalWorkoutRecord(revision = 9L)),
            0L,
        )
        assertEquals(1, applied.appliedRoots)
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.localGenerationWatermark,
        )

        val second = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, CANONICAL_WORKOUT_A, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(CANONICAL_WORKOUT_A, "W2", STARTED_MILLIS) }
        }!!
        assertEquals(6L, second.request?.envelope?.localGeneration)
        assertEquals(
            6L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.localGenerationWatermark,
        )
    }

    // Acceptance 6: structural preflight and retained-evidence validation.

    @Test
    fun structurallyInvalidBatchesRejected() = runTest {
        val base = validWorkoutUpsertBatch()
        val change = base.changes.single()
        assertInvalidBatchRejected(base.copy(changes = listOf(change, change)))
        assertInvalidBatchRejected(base.copy(changes = listOf(change.copy(aggregateId = CANONICAL_WORKOUT_B))))
        assertInvalidBatchRejected(base.copy(changes = listOf(change.copy(aggregateType = AggregateType.ROUTINE))))
        assertInvalidBatchRejected(base.copy(changes = listOf(change.copy(revision = 2L))))
        assertInvalidBatchRejected(base.copy(changes = listOf(change.copy(record = null))))
        assertInvalidBatchRejected(base.copy(changes = emptyList()))
        assertInvalidBatchRejected(base.copy(batchId = "not-a-uuid"))
        assertInvalidBatchRejected(base.copy(cursor = ""))
        assertInvalidBatchRejected(base.copy(changes = listOf(change.copy(revision = 0L))))
        assertInvalidBatchRejected(
            base.copy(
                changes = listOf(
                    change.copy(operation = ChangeOperation.DELETE),
                ),
            ),
        )
    }

    @Test
    fun malformedWireValuesRejected() = runTest {
        val valid = canonicalPull(
            changes = canonicalJsonArray(
                canonicalChange(
                    CANONICAL_TYPE_WORKOUT,
                    CANONICAL_WORKOUT_A,
                    CANONICAL_OP_UPSERT,
                    1L,
                    canonicalWorkoutRecord(
                        exercises = canonicalJsonArray(canonicalWorkoutEntry()),
                    ),
                ),
            ),
        )
        assertInvalidPullRejected(valid.replace("\"position\":0", "\"position\":-1"))
        assertInvalidPullRejected(valid.replace("\"restDurationSeconds\":30", "\"restDurationSeconds\":-1"))
        assertInvalidPullRejected(valid.replace("\"status\":\"active\"", "\"status\":\"archived\""))
        assertInvalidPullRejected(valid.replace("\"aggregateType\":\"workout\"", "\"aggregateType\":\"workoutX\""))
        assertInvalidPullRejected(valid.replace("\"operation\":\"upsert\"", "\"operation\":\"merge\""))
        assertInvalidPullRejected(valid.replace("\"revision\":1", "\"revision\":0"))
        assertInvalidPullRejected(valid.replace(CANONICAL_WORKOUT_A, "not-a-uuid"))
        assertInvalidPullRejected(valid.replace("\"name\":\"W\",", ""))
    }

    @Test
    fun retainedUnknownStateFailsClosed() = runTest {
        assertRetainedTargetFailsClosed(
            seedCorrupt = {
                database.canonicalSeedCanonicalRow(
                    CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, 5L, "bogus",
                    canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 5L).toString(), 0L, 0L,
                )
            },
            batch = validWorkoutUpsertBatch(
                id = CANONICAL_WORKOUT_B,
                revision = 6L,
                record = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 6L),
            ),
            verifyPreserved = {
                val retained =
                    database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!
                assertEquals(5L, retained.serverRevision)
                assertEquals("bogus", retained.canonicalState)
                assertEquals(0L, retained.localGenerationWatermark)
                assertEquals(0L, retained.cacheEpoch)
            },
        )
    }

    @Test
    fun retainedNegativeWatermarkFailsClosed() = runTest {
        assertRetainedTargetFailsClosed(
            seedCorrupt = {
                database.canonicalSeedCanonicalRow(
                    CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B, 5L, "upsert",
                    canonicalRoutineRecord(id = CANONICAL_ROUTINE_B, revision = 5L).toString(), -4L, 0L,
                )
            },
            batch = canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B, CANONICAL_OP_UPSERT, 6L,
                            canonicalRoutineRecord(id = CANONICAL_ROUTINE_B, revision = 6L),
                        ),
                    ),
                ),
            ),
            verifyPreserved = {
                val retained = database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B)!!
                assertEquals(5L, retained.serverRevision)
                assertEquals("upsert", retained.canonicalState)
                assertEquals(-4L, retained.localGenerationWatermark)
            },
        )
    }

    @Test
    fun retainedUpsertNullJsonFailsClosed() = runTest {
        assertRetainedTargetFailsClosed(
            seedCorrupt = {
                database.canonicalSeedCanonicalRow(
                    CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B, 5L, "upsert", null, 0L, 0L,
                )
            },
            batch = canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B, CANONICAL_OP_UPSERT, 6L,
                            canonicalCustomRecord(id = CANONICAL_CUSTOM_B, revision = 6L),
                        ),
                    ),
                ),
            ),
            verifyPreserved = {
                val retained = database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_B)!!
                assertEquals(5L, retained.serverRevision)
                assertEquals("upsert", retained.canonicalState)
                assertNull(retained.canonicalJson)
            },
        )
    }

    @Test
    fun retainedUpsertIdMismatchFailsClosed() = runTest {
        assertRetainedTargetFailsClosed(
            seedCorrupt = {
                database.canonicalSeedCanonicalRow(
                    CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C, 5L, "upsert",
                    canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 5L).toString(), 0L, 0L,
                )
            },
            batch = validWorkoutUpsertBatch(
                id = CANONICAL_WORKOUT_C,
                revision = 6L,
                record = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_C, revision = 6L),
            ),
            verifyPreserved = {
                val retained = database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C)!!
                assertEquals(5L, retained.serverRevision)
                assertEquals(
                    CANONICAL_WORKOUT_B,
                    JSONObject(retained.canonicalJson!!).getString("id"),
                )
            },
        )
    }

    @Test
    fun retainedDeleteWithJsonFailsClosed() = runTest {
        val target = "aaaaaaaa-0000-4000-8000-000000000011"
        val json = canonicalWorkoutRecord(id = target, revision = 5L).toString()
        assertRetainedTargetFailsClosed(
            seedCorrupt = {
                database.canonicalSeedCanonicalRow(
                    CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, target, 5L, "delete", json, 0L, 0L,
                )
            },
            batch = canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, target, CANONICAL_OP_DELETE, 6L, null),
                    ),
                ),
            ),
            verifyPreserved = {
                val retained =
                    database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, target)!!
                assertEquals("delete", retained.canonicalState)
                assertEquals(json, retained.canonicalJson)
            },
        )
    }

    @Test
    fun retainedUnobservedWithRevisionFailsClosed() = runTest {
        val target = "aaaaaaaa-0000-4000-8000-000000000012"
        assertRetainedTargetFailsClosed(
            seedCorrupt = {
                database.canonicalSeedCanonicalRow(
                    CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, target, 5L, "unobserved", null, 0L, 0L,
                )
            },
            batch = canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_ROUTINE, target, CANONICAL_OP_UPSERT, 6L,
                            canonicalRoutineRecord(id = target, revision = 6L),
                        ),
                    ),
                ),
            ),
            verifyPreserved = {
                val retained = database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, target)!!
                assertEquals("unobserved", retained.canonicalState)
                assertEquals(5L, retained.serverRevision)
                assertNull(retained.canonicalJson)
            },
        )
    }

    @Test
    fun duplicateMemberRetainedJsonFailsClosed() = runTest {
        database.canonicalSeedDevice()
        val duplicated = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 5L).toString()
            .replace("\"name\":\"W\"", "\"name\":\"W\",\"name\":\"W-renamed\"")
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, 5L, "upsert", duplicated, 0L, 0L,
        )
        try {
            canonicalStore(database).applyBatch(
                validWorkoutUpsertBatch(
                    id = CANONICAL_WORKOUT_B,
                    revision = 6L,
                    record = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 6L),
                ),
                0L,
            )
            fail("Expected CanonicalStorageException")
        } catch (_: CanonicalStorageException) {
        }
        val retained = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!
        assertEquals(5L, retained.serverRevision)
        assertEquals(duplicated, retained.canonicalJson)
    }

    @Test
    fun invalidCacheCountersFailClosed() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, false, -1L, false, 0L)
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), 0L)
            fail("Expected CanonicalStorageException for negative cache epoch")
        } catch (_: CanonicalStorageException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())

        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_account_cache")
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, true, 3L, false, 4L)
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), 3L)
            fail("Expected CanonicalStorageException for materialized epoch above cache epoch")
        } catch (_: CanonicalStorageException) {
        }
        assertEquals(0, database.canonicalCanonicalCount())
        assertEquals(false, database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)?.projectionDirty)
    }

    @Test
    fun rootEpochAboveAccountEpochFailsClosed() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, false, 3L, false, 0L)
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, 5L, "upsert",
            canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 5L).toString(), 0L, 5L,
        )
        try {
            canonicalStore(database).applyBatch(validWorkoutUpsertBatch(), 3L)
            fail("Expected CanonicalStorageException")
        } catch (_: CanonicalStorageException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)?.serverRevision,
        )
    }

    // Acceptance 7: family identity and dependency boundary.

    @Test
    fun higherRevisionReparentRejected() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 1L,
                record = canonicalWorkoutRecord(
                    revision = 1L,
                    exercises = canonicalJsonArray(
                        canonicalWorkoutEntry(
                            id = CANONICAL_ENTRY_A1,
                            sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1)),
                        ),
                    ),
                ),
            ),
            0L,
        )
        try {
            store.applyBatch(
                validWorkoutUpsertBatch(
                    revision = 2L,
                    record = canonicalWorkoutRecord(
                        revision = 2L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                id = CANONICAL_ENTRY_A2,
                                position = 0,
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_SET_A1, position = 0)),
                            ),
                        ),
                    ),
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for same-root reparent")
        } catch (_: CanonicalConflictException) {
        }
        try {
            store.applyBatch(
                validWorkoutUpsertBatch(
                    id = CANONICAL_WORKOUT_B,
                    revision = 1L,
                    record = canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_B,
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
                    ),
                    batchId = "33333333-3333-3333-3333-333333333333",
                    cursor = "cursor-3",
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for cross-root entry move")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
        assertEquals(
            1L,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.serverRevision,
        )
    }

    @Test
    fun sameBatchDeleteOldRootAndMoveEntryRejected() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 1L,
                record = canonicalWorkoutRecord(
                    revision = 1L,
                    exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
                ),
            ),
            0L,
        )
        try {
            store.applyBatch(
                canonicalDecodeBatch(
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
                                    revision = 1L,
                                    exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
                                ),
                            ),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        val retained = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(1L, retained.serverRevision)
        assertEquals("upsert", retained.canonicalState)
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
    }

    @Test
    fun rootIdReusedAsEntryOrSetRejected() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(validWorkoutUpsertBatch(revision = 2L), 0L)
        try {
            store.applyBatch(
                validWorkoutUpsertBatch(
                    id = CANONICAL_WORKOUT_B,
                    revision = 1L,
                    record = canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_B,
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_WORKOUT_A)),
                    ),
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for root reused as entry")
        } catch (_: CanonicalConflictException) {
        }
        try {
            store.applyBatch(
                validWorkoutUpsertBatch(
                    id = CANONICAL_WORKOUT_C,
                    revision = 1L,
                    record = canonicalWorkoutRecord(
                        id = CANONICAL_WORKOUT_C,
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_WORKOUT_A)),
                            ),
                        ),
                    ),
                    batchId = "33333333-3333-3333-3333-333333333333",
                    cursor = "cursor-3",
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for root reused as set")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C))
    }

    @Test
    fun sameBatchDuplicateChildBindingRejected() = runTest {
        database.canonicalSeedDevice()
        try {
            canonicalStore(database).applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        changes = canonicalJsonArray(
                            canonicalChange(
                                CANONICAL_TYPE_WORKOUT,
                                CANONICAL_WORKOUT_A,
                                CANONICAL_OP_UPSERT,
                                1L,
                                canonicalWorkoutRecord(
                                    revision = 1L,
                                    exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
                                ),
                            ),
                            canonicalChange(
                                CANONICAL_TYPE_WORKOUT,
                                CANONICAL_WORKOUT_B,
                                CANONICAL_OP_UPSERT,
                                1L,
                                canonicalWorkoutRecord(
                                    id = CANONICAL_WORKOUT_B,
                                    revision = 1L,
                                    exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
                                ),
                            ),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
    }

    @Test
    fun independentFamilyAndAccountSameUuidsSucceed() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_OTHER_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C, 2L, "upsert",
            canonicalWorkoutRecord(id = CANONICAL_WORKOUT_C, revision = 2L).toString(), 7L, 1L,
        )
        val result = canonicalStore(database).applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_WORKOUT,
                            CANONICAL_WORKOUT_B,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
                        ),
                        canonicalChange(
                            CANONICAL_TYPE_ROUTINE,
                            CANONICAL_WORKOUT_B,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalRoutineRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
                        ),
                        canonicalChange(
                            CANONICAL_TYPE_WORKOUT,
                            CANONICAL_WORKOUT_C,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalWorkoutRecord(id = CANONICAL_WORKOUT_C, revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(3, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        assertNotNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
        assertNotNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_WORKOUT_B))
        assertNotNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C))
        val other = database.syncCanonicalDao().getRoot(CANONICAL_OTHER_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C)!!
        assertEquals(2L, other.serverRevision)
        assertEquals(7L, other.localGenerationWatermark)
    }

    @Test
    fun missingCustomDependencyStoredThenArrivingLater() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val workout = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 1L,
                record = canonicalWorkoutRecord(
                    revision = 1L,
                    exercises = canonicalJsonArray(
                        canonicalWorkoutEntry(exerciseId = CANONICAL_CUSTOM_A),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, workout.appliedRoots)
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A))
        val custom = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM,
                            CANONICAL_CUSTOM_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalCustomRecord(revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, custom.appliedRoots)
        assertNotNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A))
    }

    @Test
    fun customDeleteWithRetainedReferenceStored() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM,
                            CANONICAL_CUSTOM_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalCustomRecord(revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
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
                ),
            ),
            0L,
        )
        val workoutStored = JSONObject(
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
        )
        assertEquals(1L, workoutStored.getLong("revision"))
        assertEquals(
            CANONICAL_CUSTOM_A,
            workoutStored.getJSONArray("exercises").getJSONObject(0).getString("exerciseId"),
        )
        val deleted = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = "33333333-3333-3333-3333-333333333333",
                    cursor = "cursor-3",
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_DELETE, 2L, null),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, deleted.appliedRoots)
        val custom = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!
        assertEquals(2L, custom.serverRevision)
        assertEquals("delete", custom.canonicalState)
    }

    @Test
    fun physicalCollisionRetainedWithoutTouchingDomain() = runTest {
        database.canonicalSeedDevice()
        seedDomainWorkout(CANONICAL_WORKOUT_A, account = CANONICAL_OTHER_ACCOUNT, name = "Foreign")
        database.routineDao().insertRoutine(
            RoutineEntity(id = CANONICAL_ROUTINE_A, name = "Local", isDraft = true, accountId = CANONICAL_ACCOUNT),
        )
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = CANONICAL_CUSTOM_A,
                name = "Local",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = true,
                accountId = CANONICAL_ACCOUNT,
            ),
        )
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = CANONICAL_CUSTOM_B,
                name = "Bundled",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = false,
                accountId = null,
            ),
        )
        val result = canonicalStore(database).applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_WORKOUT,
                            CANONICAL_WORKOUT_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalWorkoutRecord(revision = 1L),
                        ),
                        canonicalChange(
                            CANONICAL_TYPE_ROUTINE,
                            CANONICAL_ROUTINE_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalRoutineRecord(revision = 1L),
                        ),
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM,
                            CANONICAL_CUSTOM_A,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalCustomRecord(revision = 1L),
                        ),
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM,
                            CANONICAL_CUSTOM_B,
                            CANONICAL_OP_UPSERT,
                            1L,
                            canonicalCustomRecord(id = CANONICAL_CUSTOM_B, revision = 1L),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(4, result.appliedRoots)
        val foreign = database.workoutDao().getWorkout(CANONICAL_WORKOUT_A)!!
        assertEquals("Foreign", foreign.name)
        assertEquals(CANONICAL_OTHER_ACCOUNT, foreign.accountId)
        assertEquals(0L, foreign.localGeneration)

        val ids = mutableListOf(MUTATION_E1, MUTATION_E2)
        val syncStore = SyncStore(database, RepositorySession(CANONICAL_ACCOUNT, CANONICAL_GENERATION)) { ids.removeFirst() }
        val routineCapture = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.ROUTINE, CANONICAL_ROUTINE_A, MutationKind.ROUTINE_UPSERT,
                changed = true,
            ) {
                MutationPayload.RoutineUpsert(
                    RoutineInputModel(id = CANONICAL_ROUTINE_A, name = "Local", isDraft = true, exercises = emptyList()),
                )
            }
        }!!
        assertEquals(1L, routineCapture.request?.envelope?.localGeneration)
        val customCapture = syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.CUSTOM_EXERCISE, CANONICAL_CUSTOM_A, MutationKind.CUSTOM_EXERCISE_UPSERT,
                changed = true,
            ) {
                MutationPayload.CustomExerciseUpsert(
                    CustomExerciseInputModel(
                        id = CANONICAL_CUSTOM_A,
                        name = "Local",
                        primaryMuscleGroupId = null,
                        secondaryMuscleGroupIds = emptyList(),
                        equipmentId = null,
                        equipmentNumItems = null,
                        category = null,
                        difficultyLevel = null,
                        forceType = null,
                        mechanics = null,
                        instructions = null,
                    ),
                )
            }
        }!!
        assertEquals(1L, customCapture.request?.envelope?.localGeneration)
    }

    @Test
    fun removedChildReappearSameBindingAccepted() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val both = canonicalJsonArray(
            canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1, position = 0),
            canonicalWorkoutEntry(id = CANONICAL_ENTRY_A2, position = 1),
        )
        val first = store.applyBatch(
            validWorkoutUpsertBatch(revision = 1L, record = canonicalWorkoutRecord(revision = 1L, exercises = both)),
            0L,
        )
        assertEquals(1, first.appliedRoots)
        val removed = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 2L,
                record = canonicalWorkoutRecord(
                    revision = 2L,
                    exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1, position = 0)),
                ),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            0L,
        )
        assertEquals(1, removed.appliedRoots)
        val reappeared = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 3L,
                record = canonicalWorkoutRecord(revision = 3L, exercises = both),
                batchId = "33333333-3333-3333-3333-333333333333",
                cursor = "cursor-3",
            ),
            0L,
        )
        assertEquals(1, reappeared.appliedRoots)
        val stored = JSONObject(
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
        )
        assertEquals(2, stored.getJSONArray("exercises").length())
        assertEquals(CANONICAL_ENTRY_A1, stored.getJSONArray("exercises").getJSONObject(0).getString("id"))
        assertEquals(CANONICAL_ENTRY_A2, stored.getJSONArray("exercises").getJSONObject(1).getString("id"))
    }

    @Test
    fun reservedTombstonedAbsentRootCannotBecomeEntryOrSet() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, null, "unobserved", null, 5L, 0L,
        )
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C, 3L, "delete", null, 0L, 0L,
        )
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B, null, "snapshot_absent", null, 0L, 0L,
        )
        val store = canonicalStore(database)
        try {
            store.applyBatch(
                validWorkoutUpsertBatch(
                    revision = 1L,
                    record = canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_WORKOUT_B)),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for reserved root reused as entry")
        } catch (_: CanonicalConflictException) {
        }
        try {
            store.applyBatch(
                validWorkoutUpsertBatch(
                    id = CANONICAL_WORKOUT_A,
                    revision = 1L,
                    record = canonicalWorkoutRecord(
                        revision = 1L,
                        exercises = canonicalJsonArray(
                            canonicalWorkoutEntry(
                                sets = canonicalJsonArray(canonicalWorkoutSet(id = CANONICAL_WORKOUT_C)),
                            ),
                        ),
                    ),
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for tombstoned root reused as set")
        } catch (_: CanonicalConflictException) {
        }
        try {
            store.applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        batchId = "33333333-3333-3333-3333-333333333333",
                        cursor = "cursor-3",
                        changes = canonicalJsonArray(
                            canonicalChange(
                                CANONICAL_TYPE_ROUTINE,
                                CANONICAL_ROUTINE_A,
                                CANONICAL_OP_UPSERT,
                                1L,
                                canonicalRoutineRecord(
                                    id = CANONICAL_ROUTINE_A,
                                    revision = 1L,
                                    exercises = canonicalJsonArray(canonicalRoutineEntry(id = CANONICAL_ROUTINE_B)),
                                ),
                            ),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for absent root reused as entry")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A))
        assertEquals(
            5L,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)?.localGenerationWatermark,
        )
        assertEquals(
            "delete",
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_C)?.canonicalState,
        )
    }

    @Test
    fun unknownIncomingDeleteCannotClaimKnownChild() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val before = canonicalWorkoutRecord(
            id = CANONICAL_WORKOUT_B,
            revision = 2L,
            exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
        ).toString()
        store.applyBatch(validWorkoutUpsertBatch(revision = 2L, record = canonicalWorkoutRecord(revision = 2L)), 0L)
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, 2L, "upsert", before, 0L, 0L,
        )
        try {
            store.applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        batchId = CANONICAL_BATCH_2,
                        cursor = CANONICAL_CURSOR_2,
                        changes = canonicalJsonArray(
                            canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_ENTRY_A1, CANONICAL_OP_DELETE, 1L, null),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException for delete claiming a known child")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_ENTRY_A1))
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!.canonicalJson,
        )
    }

    @Test
    fun sameBatchDeleteAndEntryRoleCollision() = runTest {
        database.canonicalSeedDevice()
        try {
            canonicalStore(database).applyBatch(
                canonicalDecodeBatch(
                    canonicalPull(
                        changes = canonicalJsonArray(
                            canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_ENTRY_A1, CANONICAL_OP_DELETE, 1L, null),
                            canonicalChange(
                                CANONICAL_TYPE_WORKOUT,
                                CANONICAL_WORKOUT_A,
                                CANONICAL_OP_UPSERT,
                                1L,
                                canonicalWorkoutRecord(
                                    revision = 1L,
                                    exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_ENTRY_A1)),
                                ),
                            ),
                        ),
                    ),
                ),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_ENTRY_A1))
    }

    @Test
    fun noopBatchDoesNotBypassInvalidRetainedEvidence() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT,
            CANONICAL_TYPE_WORKOUT,
            CANONICAL_WORKOUT_A,
            5L,
            "upsert",
            canonicalWorkoutRecord(revision = 5L).toString(),
            0L,
            0L,
        )
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B, 5L, "bogus",
            canonicalRoutineRecord(id = CANONICAL_ROUTINE_B, revision = 5L).toString(), 0L, 0L,
        )
        try {
            canonicalStore(database).applyBatch(
                validWorkoutUpsertBatch(
                    revision = 4L,
                    record = canonicalWorkoutRecord(revision = 4L),
                ),
                0L,
            )
            fail("Expected CanonicalStorageException")
        } catch (_: CanonicalStorageException) {
        }
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)?.serverRevision,
        )
        assertEquals(
            "bogus",
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_B)?.canonicalState,
        )
        assertNull(database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT))
    }

    @Test
    fun unobservedRootAcceptsFirstObservationRegardlessOfWatermark() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, null, "unobserved", null, 9L, 0L,
        )
        val result = canonicalStore(database).applyBatch(validWorkoutUpsertBatch(revision = 1L), 0L)
        assertEquals(1, result.appliedRoots)
        val root = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(1L, root.serverRevision)
        assertEquals("upsert", root.canonicalState)
        assertEquals(9L, root.localGenerationWatermark)
    }

    @Test
    fun snapshotAbsentLowerEqualNoopHigherApplies() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, 4L, "snapshot_absent", null, 0L, 0L,
        )
        val store = canonicalStore(database)
        val lower = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 3L,
                record = canonicalWorkoutRecord(revision = 3L),
            ),
            0L,
        )
        assertEquals(0, lower.appliedRoots)
        assertEquals(1, lower.ignoredRoots)
        val retained = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals("snapshot_absent", retained.canonicalState)
        assertNull(retained.canonicalJson)

        val equal = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 4L,
                record = canonicalWorkoutRecord(revision = 4L),
                batchId = "55555555-5555-5555-5555-555555555555",
                cursor = "cursor-5",
            ),
            0L,
        )
        assertEquals(0, equal.appliedRoots)
        assertEquals(1, equal.ignoredRoots)
        val stillAbsent = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals("snapshot_absent", stillAbsent.canonicalState)
        assertNull(stillAbsent.canonicalJson)
        assertEquals(4L, stillAbsent.serverRevision)

        val higher = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 5L,
                record = canonicalWorkoutRecord(revision = 5L),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            0L,
        )
        assertEquals(1, higher.appliedRoots)
        val applied = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!
        assertEquals(5L, applied.serverRevision)
        assertEquals("upsert", applied.canonicalState)

        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, 4L, "snapshot_absent", null, 0L, 0L,
        )
        val lowerDelete = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = "33333333-3333-3333-3333-333333333333",
                    cursor = "cursor-3",
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, CANONICAL_OP_DELETE, 3L, null),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(0, lowerDelete.appliedRoots)
        assertEquals(1, lowerDelete.ignoredRoots)
        assertEquals(
            "snapshot_absent",
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)?.canonicalState,
        )
        val equalDelete = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = "55555555-5555-5555-5555-555555555556",
                    cursor = "cursor-6",
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, CANONICAL_OP_DELETE, 4L, null),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(0, equalDelete.appliedRoots)
        assertEquals(1, equalDelete.ignoredRoots)
        assertEquals(
            "snapshot_absent",
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)?.canonicalState,
        )
        val higherDelete = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = "44444444-4444-4444-4444-444444444444",
                    cursor = "cursor-4",
                    changes = canonicalJsonArray(
                        canonicalChange(CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, CANONICAL_OP_DELETE, 5L, null),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, higherDelete.appliedRoots)
        val deleted = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!
        assertEquals("delete", deleted.canonicalState)
        assertEquals(5L, deleted.serverRevision)
    }

    // Acceptance 8: caller-owned input admission.

    @Test
    fun mutableInputAdmissionRaceUsesAdmittedCopy() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val admitted = validWorkoutUpsertBatch()
        val swappedChange = canonicalDecodeBatch(
            canonicalPull(
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT,
                        CANONICAL_WORKOUT_B,
                        CANONICAL_OP_UPSERT,
                        1L,
                        canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
                    ),
                ),
            ),
        ).changes.single()
        val acquired = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val holder = launch {
            store.withFencedTransaction(0L) {
                acquired.complete(Unit)
                gate.await()
            }
        }
        acquired.await()
        val mutableChanges = ArrayList(admitted.changes)
        val mutableBatch = admitted.copy(changes = mutableChanges)
        val child = async { store.applyBatch(mutableBatch, 0L) }
        testScheduler.runCurrent()
        mutableChanges.clear()
        mutableChanges.add(swappedChange)
        gate.complete(Unit)
        val result = child.await()
        holder.join()
        assertEquals(1, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        assertNotNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A))
        assertNull(database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B))
        assertEquals(
            1L,
            JSONObject(
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
            ).getLong("revision"),
        )
    }

    @Test
    fun customObservationMuscleAndEquipmentRelations() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val muscles = canonicalJsonArray(
            canonicalMuscleRef("aaaaaaaa-0000-4000-8000-000000000021", "Chest", "primary"),
            canonicalMuscleRef("aaaaaaaa-0000-4000-8000-000000000022", "Shoulders", "secondary"),
        )
        val equipment = canonicalJsonArray(
            canonicalEquipmentRef("aaaaaaaa-0000-4000-8000-000000000023", "Dumbbell", 2),
        )
        val applied = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 1L,
                            canonicalCustomRecord(
                                id = CANONICAL_CUSTOM_A,
                                name = "Fly",
                                revision = 1L,
                                category = "strength",
                                instructions = canonicalJsonArray().let {
                                    it.put("Set up")
                                    it
                                },
                                muscleGroups = muscles,
                                equipment = equipment,
                            ),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, applied.appliedRoots)
        val stored = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson!!,
        )
        assertEquals("Fly", stored.getString("name"))
        assertEquals(1L, stored.getLong("revision"))
        assertEquals("strength", stored.getString("category"))
        assertEquals(1, stored.getJSONArray("instructions").length())
        assertEquals("Set up", stored.getJSONArray("instructions").getString(0))
        val storedMuscles = stored.getJSONArray("muscleGroups")
        assertEquals(2, storedMuscles.length())
        assertEquals("aaaaaaaa-0000-4000-8000-000000000021", storedMuscles.getJSONObject(0).getString("id"))
        assertEquals("Chest", storedMuscles.getJSONObject(0).getString("name"))
        assertEquals("primary", storedMuscles.getJSONObject(0).getString("type"))
        assertEquals("Shoulders", storedMuscles.getJSONObject(1).getString("name"))
        assertEquals("secondary", storedMuscles.getJSONObject(1).getString("type"))
        val storedEquipment = stored.getJSONArray("equipment")
        assertEquals(1, storedEquipment.length())
        assertEquals("Dumbbell", storedEquipment.getJSONObject(0).getString("name"))
        assertEquals(2, storedEquipment.getJSONObject(0).getInt("numItems"))

        val renamed = store.applyBatch(
            canonicalDecodeBatch(
                canonicalPull(
                    batchId = CANONICAL_BATCH_2,
                    cursor = CANONICAL_CURSOR_2,
                    changes = canonicalJsonArray(
                        canonicalChange(
                            CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A, CANONICAL_OP_UPSERT, 2L,
                            canonicalCustomRecord(
                                id = CANONICAL_CUSTOM_A,
                                name = "Fly Plus",
                                revision = 2L,
                                category = "strength",
                                muscleGroups = muscles,
                                equipment = canonicalJsonArray(
                                    canonicalEquipmentRef("aaaaaaaa-0000-4000-8000-000000000024", "Bench", null),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            0L,
        )
        assertEquals(1, renamed.appliedRoots)
        val after = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson!!,
        )
        assertEquals("Fly Plus", after.getString("name"))
        assertEquals(2, after.getJSONArray("muscleGroups").length())
        assertEquals(1, after.getJSONArray("equipment").length())
        assertEquals("Bench", after.getJSONArray("equipment").getJSONObject(0).getString("name"))
        assertTrue(after.getJSONArray("equipment").getJSONObject(0).isNull("numItems"))
    }

    @Test
    fun equalRevisionMemberOrderNormalizationNoop() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 2L,
                record = canonicalWorkoutRecord(
                    revision = 2L,
                    exercises = canonicalJsonArray(
                        canonicalWorkoutEntry(
                            id = CANONICAL_ENTRY_A1,
                            position = 0,
                            sets = canonicalJsonArray(
                                canonicalWorkoutSet(id = CANONICAL_SET_A1, position = 0, repetitions = 8, weightKg = 100.0),
                                canonicalWorkoutSet(id = CANONICAL_SET_A2, position = 1, repetitions = 5, weightKg = 60.0),
                            ),
                        ),
                        canonicalWorkoutEntry(id = CANONICAL_ENTRY_A2, position = 1),
                    ),
                ),
            ),
            0L,
        )
        val before = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        val result = store.applyBatch(
            validWorkoutUpsertBatch(
                revision = 2L,
                record = canonicalWorkoutRecord(
                    revision = 2L,
                    exercises = canonicalJsonArray(
                        canonicalWorkoutEntry(id = CANONICAL_ENTRY_A2, position = 1),
                        canonicalWorkoutEntry(
                            id = CANONICAL_ENTRY_A1,
                            position = 0,
                            sets = canonicalJsonArray(
                                canonicalWorkoutSet(id = CANONICAL_SET_A2, position = 1, repetitions = 5, weightKg = 60.0),
                                canonicalWorkoutSet(id = CANONICAL_SET_A1, position = 0, repetitions = 8, weightKg = 100.0),
                            ),
                        ),
                    ),
                ),
                batchId = CANONICAL_BATCH_2,
                cursor = CANONICAL_CURSOR_2,
            ),
            0L,
        )
        assertEquals(0, result.appliedRoots)
        assertEquals(1, result.ignoredRoots)
        assertEquals(
            before,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
        )
    }

    @Test
    fun noopBatchDoesNotBypassCorruptFamilyBindings() = runTest {
        database.canonicalSeedDevice()
        val goodJson = canonicalWorkoutRecord(revision = 5L).toString()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, 5L, "upsert", goodJson, 0L, 0L,
        )
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, 5L, "upsert",
            canonicalWorkoutRecord(
                id = CANONICAL_WORKOUT_B,
                revision = 5L,
                exercises = canonicalJsonArray(canonicalWorkoutEntry(id = CANONICAL_WORKOUT_A)),
            ).toString(),
            0L, 0L,
        )
        // Retained root/child role incompatibility surfaces as a conflict; the
        // all-no-op batch still fails closed with nothing written.
        try {
            canonicalStore(database).applyBatch(
                validWorkoutUpsertBatch(revision = 4L, record = canonicalWorkoutRecord(revision = 4L)),
                0L,
            )
            fail("Expected CanonicalConflictException")
        } catch (_: CanonicalConflictException) {
        }
        assertEquals(
            goodJson,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
        )
        assertEquals(
            5L,
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)?.serverRevision,
        )
        assertNull(database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT))
    }

    @Test
    fun noopBatchOnCleanCacheKeepsDirtyFalse() = runTest {
        database.canonicalSeedDevice()
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, true, 3L, false, 2L)
        val json = canonicalWorkoutRecord(revision = 5L).toString()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, 5L, "upsert", json, 0L, 3L,
        )
        val result = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(revision = 4L, record = canonicalWorkoutRecord(revision = 4L)),
            3L,
        )
        assertEquals(0, result.appliedRoots)
        assertEquals(1, result.ignoredRoots)
        assertEquals(
            json,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
        )
        val cache = database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)!!
        assertEquals(true, cache.baselineComplete)
        assertEquals(3L, cache.cacheEpoch)
        assertEquals(false, cache.projectionDirty)
        assertEquals(2L, cache.materializedEpoch)
    }

    @Test
    fun selectedBatchPreservesDomainOutboxSyncStateExactly() = runTest {
        database.canonicalSeedDevice()
        insertExercises(
            ExerciseEntity(
                id = CANONICAL_EXERCISE_REF,
                name = "Catalogue",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = false,
                accountId = null,
            ),
        )
        insertWorkoutAggregate(
            WorkoutEntity(
                id = CANONICAL_WORKOUT_A,
                name = "Local",
                startedAtMillis = 1_000L,
                accountId = CANONICAL_ACCOUNT,
                serverRevision = 5L,
                localGeneration = 2L,
            ),
            exercises = listOf(
                WorkoutExerciseEntity("we-1", CANONICAL_WORKOUT_A, CANONICAL_EXERCISE_REF, 0, "n", 30),
            ),
            sets = listOf(
                WorkoutSetEntity("ws-1", "we-1", 0, 8, 50.0, true, 60),
            ),
        )
        insertRoutineAggregate(
            RoutineEntity(id = CANONICAL_ROUTINE_A, name = "Local", isDraft = true, accountId = CANONICAL_ACCOUNT),
            exercises = listOf(
                RoutineExerciseEntity("re-1", CANONICAL_ROUTINE_A, CANONICAL_EXERCISE_REF, 0, 30),
            ),
            sets = listOf(
                RoutineSetEntity("rs-1", "re-1", 0, 10, 40.0),
            ),
        )
        insertExercises(
            ExerciseEntity(
                id = CANONICAL_CUSTOM_A,
                name = "Local",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = "strength",
                instructions = listOf("Keep"),
                isUserCreated = true,
                accountId = CANONICAL_ACCOUNT,
            ),
        )
        insertMuscleGroups(MuscleGroupEntity("mg-1", "mg-1", "Chest"))
        insertEquipment(EquipmentEntity("eq-1", "Dumbbell", "weight", null))
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO exercise_muscle_groups (exercise_id, muscle_group_id, type) VALUES (?, ?, ?)",
            arrayOf(CANONICAL_CUSTOM_A, "mg-1", "primary"),
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO exercise_equipment (exercise_id, equipment_id, num_items) VALUES (?, ?, ?)",
            arrayOf<Any?>(CANONICAL_CUSTOM_A, "eq-1", 2),
        )
        database.syncDao().upsertSyncState(SyncStateEntity(CANONICAL_ACCOUNT, "cursor-9", 12345L))
        database.canonicalSeedCacheRow(CANONICAL_ACCOUNT, true, 0L, false, 0L)
        val ids = mutableListOf(MUTATION_E1, MUTATION_E2)
        val syncStore = SyncStore(database, RepositorySession(CANONICAL_ACCOUNT, CANONICAL_GENERATION)) { ids.removeFirst() }
        syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, CANONICAL_WORKOUT_A, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(CANONICAL_WORKOUT_A, "Local", STARTED_MILLIS) }
        }
        syncStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT, CANONICAL_WORKOUT_A, MutationKind.WORKOUT_START,
                changed = true,
            ) { MutationPayload.WorkoutStart(CANONICAL_WORKOUT_A, "Local 2", STARTED_MILLIS) }
        }
        database.syncDao().freezePendingMutation(MUTATION_E1, CANONICAL_ACCOUNT)
        val domainBefore = snapshotDomain()
        val outboxBefore = database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT)
        assertEquals(2, outboxBefore.size)
        val syncBefore = database.syncDao().getSyncState(CANONICAL_ACCOUNT)!!
        assertEquals("cursor-9", syncBefore.cursor)
        assertEquals(12345L, syncBefore.lastSuccessMillis)

        val result = canonicalStore(database).applyBatch(
            validWorkoutUpsertBatch(
                id = CANONICAL_WORKOUT_B,
                revision = 1L,
                record = canonicalWorkoutRecord(id = CANONICAL_WORKOUT_B, revision = 1L),
            ),
            0L,
        )
        assertEquals(1, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        assertEquals(domainBefore, snapshotDomain())
        assertEquals(outboxBefore, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT))
        val syncAfter = database.syncDao().getSyncState(CANONICAL_ACCOUNT)!!
        assertEquals("cursor-9", syncAfter.cursor)
        assertEquals(12345L, syncAfter.lastSuccessMillis)
        val cache = database.syncCanonicalDao().getAccountCache(CANONICAL_ACCOUNT)!!
        assertEquals(true, cache.baselineComplete)
        assertEquals(0L, cache.cacheEpoch)
        assertEquals(true, cache.projectionDirty)
        assertEquals(0L, cache.materializedEpoch)
    }

    @Test
    fun directConstructedValidBatchApplies() = runTest {
        database.canonicalSeedDevice()
        val batch = SyncBatchModel(
            batchId = CANONICAL_BATCH_1,
            cursor = CANONICAL_CURSOR_1,
            changes = listOf(
                SyncChangeModel(
                    aggregateType = AggregateType.WORKOUT,
                    aggregateId = CANONICAL_WORKOUT_A,
                    operation = ChangeOperation.UPSERT,
                    revision = 1L,
                    record = CanonicalRecord.Workout(
                        WorkoutRecordModel(
                            id = CANONICAL_WORKOUT_A,
                            name = "W",
                            status = "active",
                            startedAtMillis = STARTED_MILLIS,
                            finishedAtMillis = null,
                            revision = 1L,
                            exercises = listOf(
                                WorkoutExerciseModel(
                                    id = CANONICAL_ENTRY_A1,
                                    exerciseId = CANONICAL_EXERCISE_REF,
                                    position = 0,
                                    notes = "Keep",
                                    restDurationSeconds = 90,
                                    sets = listOf(
                                        WorkoutSetModel(
                                            id = CANONICAL_SET_A1,
                                            position = 0,
                                            repetitions = 8,
                                            weightKg = 100.0,
                                            isCompleted = true,
                                            restSeconds = 87,
                                        ),
                                    ),
                                    exerciseName = "Press",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = canonicalStore(database).applyBatch(batch, 0L)
        assertEquals(1, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        val stored = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
        )
        assertEquals("W", stored.getString("name"))
        assertEquals("Keep", stored.getJSONArray("exercises").getJSONObject(0).getString("notes"))
        assertEquals(
            8,
            stored.getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
                .getInt("repetitions"),
        )
    }

    @Test
    fun directMalformedPositionsValuesRejected() = runTest {
        val validSet = WorkoutSetModel(CANONICAL_SET_A1, 0, 8, 50.0, false, null)
        val baseEntry = WorkoutExerciseModel(
            id = CANONICAL_ENTRY_A1,
            exerciseId = CANONICAL_EXERCISE_REF,
            position = 0,
            notes = "",
            restDurationSeconds = 30,
            sets = listOf(validSet),
            exerciseName = "Press",
        )
        fun workoutBatch(entry: WorkoutExerciseModel): SyncBatchModel =
            SyncBatchModel(
                batchId = CANONICAL_BATCH_1,
                cursor = CANONICAL_CURSOR_1,
                changes = listOf(
                    SyncChangeModel(
                        aggregateType = AggregateType.WORKOUT,
                        aggregateId = CANONICAL_WORKOUT_A,
                        operation = ChangeOperation.UPSERT,
                        revision = 1L,
                        record = CanonicalRecord.Workout(
                            WorkoutRecordModel(
                                CANONICAL_WORKOUT_A, "W", "active", STARTED_MILLIS, null, 1L, listOf(entry),
                            ),
                        ),
                    ),
                ),
            )
        listOf(
            baseEntry.copy(position = -1),
            baseEntry.copy(restDurationSeconds = -1),
            baseEntry.copy(sets = listOf(validSet.copy(position = -1))),
            baseEntry.copy(sets = listOf(validSet.copy(repetitions = -1))),
            baseEntry.copy(sets = listOf(validSet.copy(weightKg = -1.0))),
        ).forEach { assertInvalidBatchRejected(workoutBatch(it)) }
        assertInvalidBatchRejected(
            SyncBatchModel(
                batchId = CANONICAL_BATCH_1,
                cursor = CANONICAL_CURSOR_1,
                changes = listOf(
                    SyncChangeModel(
                        aggregateType = AggregateType.ROUTINE,
                        aggregateId = CANONICAL_ROUTINE_A,
                        operation = ChangeOperation.UPSERT,
                        revision = 1L,
                        record = CanonicalRecord.Routine(
                            RoutineRecordModel(
                                CANONICAL_ROUTINE_A, "R", false, 1L,
                                listOf(
                                    RoutineExerciseModel(
                                        CANONICAL_ENTRY_A1, CANONICAL_EXERCISE_REF, -1, 30,
                                        listOf(RoutineSetModel(CANONICAL_SET_A1, 0, 8, 50.0)), "Press",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun directInvalidStatusAndCustomRejected() = runTest {
        fun workoutBatch(status: String): SyncBatchModel =
            SyncBatchModel(
                batchId = CANONICAL_BATCH_1,
                cursor = CANONICAL_CURSOR_1,
                changes = listOf(
                    SyncChangeModel(
                        aggregateType = AggregateType.WORKOUT,
                        aggregateId = CANONICAL_WORKOUT_A,
                        operation = ChangeOperation.UPSERT,
                        revision = 1L,
                        record = CanonicalRecord.Workout(
                            WorkoutRecordModel(
                                CANONICAL_WORKOUT_A, "W", status, STARTED_MILLIS, null, 1L, emptyList(),
                            ),
                        ),
                    ),
                ),
            )
        assertInvalidBatchRejected(workoutBatch("archived"))
        fun customBatch(
            type: String = "reps",
            isUserCreated: Boolean = true,
            muscleType: String = "primary",
        ): SyncBatchModel =
            SyncBatchModel(
                batchId = CANONICAL_BATCH_1,
                cursor = CANONICAL_CURSOR_1,
                changes = listOf(
                    SyncChangeModel(
                        aggregateType = AggregateType.CUSTOM_EXERCISE,
                        aggregateId = CANONICAL_CUSTOM_A,
                        operation = ChangeOperation.UPSERT,
                        revision = 1L,
                        record = CanonicalRecord.CustomExercise(
                            CustomExerciseRecordModel(
                                CANONICAL_CUSTOM_A, "Custom", type, null, null, null, null, null,
                                isUserCreated, 1L,
                                listOf(MuscleGroupRefModel("aaaaaaaa-0000-4000-8000-000000000031", "Chest", muscleType)),
                                listOf(EquipmentRefModel("aaaaaaaa-0000-4000-8000-000000000032", "Barbell", 2)),
                            ),
                        ),
                    ),
                ),
            )
        assertInvalidBatchRejected(customBatch(type = "cardio"))
        assertInvalidBatchRejected(customBatch(isUserCreated = false))
        assertInvalidBatchRejected(customBatch(muscleType = "tertiary"))
    }

    @Test
    fun directInvalidFamilyAliasesRejected() = runTest {
        val entry = WorkoutExerciseModel(
            CANONICAL_ENTRY_A1, CANONICAL_EXERCISE_REF, 0, "", 30,
            listOf(WorkoutSetModel(CANONICAL_SET_A1, 0, 8, 50.0, false, null)), "Press",
        )
        fun workoutBatch(entries: List<WorkoutExerciseModel>): SyncBatchModel =
            SyncBatchModel(
                batchId = CANONICAL_BATCH_1,
                cursor = CANONICAL_CURSOR_1,
                changes = listOf(
                    SyncChangeModel(
                        aggregateType = AggregateType.WORKOUT,
                        aggregateId = CANONICAL_WORKOUT_A,
                        operation = ChangeOperation.UPSERT,
                        revision = 1L,
                        record = CanonicalRecord.Workout(
                            WorkoutRecordModel(
                                CANONICAL_WORKOUT_A, "W", "active", STARTED_MILLIS, null, 1L, entries,
                            ),
                        ),
                    ),
                ),
            )
        assertInvalidBatchRejected(workoutBatch(listOf(entry, entry.copy(position = 1))))
        assertInvalidBatchRejected(
            workoutBatch(
                listOf(
                    entry.copy(
                        sets = listOf(
                            WorkoutSetModel(CANONICAL_ENTRY_A1, 0, 8, 50.0, false, null),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun nestedInputOwnershipUsesAdmittedCopies() = runTest {
        database.canonicalSeedDevice()
        val store = canonicalStore(database)
        val workoutSets = ArrayList(
            listOf(WorkoutSetModel(CANONICAL_SET_A1, 0, 8, 100.0, true, 87)),
        )
        val workoutEntries = ArrayList(
            listOf(
                WorkoutExerciseModel(
                    CANONICAL_ENTRY_A1, CANONICAL_EXERCISE_REF, 0, "", 30, workoutSets, "Press",
                ),
            ),
        )
        val routineSets = ArrayList(
            listOf(RoutineSetModel(CANONICAL_SET_A2, 0, 5, 60.0)),
        )
        val routineEntries = ArrayList(
            listOf(
                RoutineExerciseModel(CANONICAL_ENTRY_A2, CANONICAL_EXERCISE_REF, 0, 30, routineSets, "Press"),
            ),
        )
        val instructions = ArrayList(listOf("Keep"))
        val muscles = ArrayList(listOf(MuscleGroupRefModel("aaaaaaaa-0000-4000-8000-000000000031", "Chest", "primary")))
        val equipment = ArrayList(listOf(EquipmentRefModel("aaaaaaaa-0000-4000-8000-000000000032", "Barbell", null)))
        val batch = SyncBatchModel(
            batchId = CANONICAL_BATCH_1,
            cursor = CANONICAL_CURSOR_1,
            changes = ArrayList(
                listOf(
                    SyncChangeModel(
                        AggregateType.WORKOUT, CANONICAL_WORKOUT_A, ChangeOperation.UPSERT, 1L,
                        CanonicalRecord.Workout(
                            WorkoutRecordModel(
                                CANONICAL_WORKOUT_A, "W", "active", STARTED_MILLIS, null, 1L, workoutEntries,
                            ),
                        ),
                    ),
                    SyncChangeModel(
                        AggregateType.ROUTINE, CANONICAL_ROUTINE_A, ChangeOperation.UPSERT, 1L,
                        CanonicalRecord.Routine(
                            RoutineRecordModel(CANONICAL_ROUTINE_A, "R", false, 1L, routineEntries),
                        ),
                    ),
                    SyncChangeModel(
                        AggregateType.CUSTOM_EXERCISE, CANONICAL_CUSTOM_A, ChangeOperation.UPSERT, 1L,
                        CanonicalRecord.CustomExercise(
                            CustomExerciseRecordModel(
                                CANONICAL_CUSTOM_A, "Custom", "reps", null, null, null, null,
                                instructions, true, 1L, muscles, equipment,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val acquired = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val holder = launch {
            store.withFencedTransaction(0L) {
                acquired.complete(Unit)
                gate.await()
            }
        }
        acquired.await()
        val child = async { store.applyBatch(batch, 0L) }
        testScheduler.runCurrent()
        workoutSets.clear()
        workoutSets.add(WorkoutSetModel("99999999-9999-9999-9999-999999999999", 0, 1, 1.0, false, null))
        workoutEntries.clear()
        routineSets.clear()
        routineEntries.clear()
        instructions.clear()
        instructions.add("Mutated")
        muscles.clear()
        equipment.clear()
        gate.complete(Unit)
        val result = child.await()
        holder.join()
        assertEquals(3, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        val workout = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
        )
        assertEquals(CANONICAL_ENTRY_A1, workout.getJSONArray("exercises").getJSONObject(0).getString("id"))
        assertEquals(
            8,
            workout.getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
                .getInt("repetitions"),
        )
        val routine = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_ROUTINE, CANONICAL_ROUTINE_A)!!.canonicalJson!!,
        )
        assertEquals(CANONICAL_ENTRY_A2, routine.getJSONArray("exercises").getJSONObject(0).getString("id"))
        assertEquals(
            5,
            routine.getJSONArray("exercises").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
                .getInt("repetitions"),
        )
        val custom = JSONObject(
            database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_CUSTOM, CANONICAL_CUSTOM_A)!!.canonicalJson!!,
        )
        assertEquals("Keep", custom.getJSONArray("instructions").getString(0))
        assertEquals(1, custom.getJSONArray("muscleGroups").length())
        assertEquals(1, custom.getJSONArray("equipment").length())
    }

    private suspend fun assertMalformedJsonFailsClosed(corruptJson: String, rootId: String) {
        database.canonicalSeedDevice()
        database.canonicalSeedCanonicalRow(
            CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, rootId, 5L, "upsert", corruptJson, 0L, 0L,
        )
        try {
            canonicalStore(database).applyBatch(
                validWorkoutUpsertBatch(
                    id = rootId,
                    revision = 6L,
                    record = canonicalWorkoutRecord(id = rootId, revision = 6L),
                ),
                0L,
            )
            fail("Expected CanonicalStorageException")
        } catch (_: CanonicalStorageException) {
        }
        val retained = database.syncCanonicalDao().getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, rootId)!!
        assertEquals(5L, retained.serverRevision)
        assertEquals("upsert", retained.canonicalState)
        assertEquals(corruptJson, retained.canonicalJson)
        assertEquals(0L, retained.localGenerationWatermark)
        assertEquals(0L, retained.cacheEpoch)
        assertEquals(1, database.canonicalCanonicalCount())
        assertEquals(0, database.canonicalCacheCount())
        assertEquals(0, database.syncDao().getOutboxForAccount(CANONICAL_ACCOUNT).size)
        assertNull(database.syncDao().getSyncState(CANONICAL_ACCOUNT))
        assertTrue(snapshotDomain().values.all { it.isEmpty() })
    }

    @Test
    fun retainedUnquotedNameTokenFailsClosed() = runTest {
        val corrupt = canonicalWorkoutRecord(revision = 5L).toString()
            .replace("\"name\":\"W\"", "\"name\":Opaque")
        assertMalformedJsonFailsClosed(corrupt, CANONICAL_WORKOUT_A)
    }

    @Test
    fun retainedNumericOverflowTokenFailsClosed() = runTest {
        val corrupt = canonicalWorkoutRecord(
            revision = 5L,
            exercises = canonicalJsonArray(canonicalWorkoutEntry(notes = "Keep")),
        ).toString().replace("\"notes\":\"Keep\"", "\"notes\":1e400")
        assertMalformedJsonFailsClosed(corrupt, CANONICAL_WORKOUT_A)
    }

    @Test
    fun retainedUnescapedNewlineFailsClosed() = runTest {
        val corrupt = canonicalWorkoutRecord(
            revision = 5L,
            exercises = canonicalJsonArray(canonicalWorkoutEntry(notes = "Keep")),
        ).toString().replace("\"notes\":\"Keep\"", "\"notes\":\"li\nne\"")
        assertMalformedJsonFailsClosed(corrupt, CANONICAL_WORKOUT_A)
    }

    @Test
    fun quotedOpaqueStringsPassUnaltered() = runTest {
        database.canonicalSeedDevice()
        val batch = canonicalDecodeBatch(
            canonicalPull(
                changes = canonicalJsonArray(
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A, CANONICAL_OP_UPSERT, 1L,
                        canonicalWorkoutRecord(revision = 1L, name = "1e400"),
                    ),
                    canonicalChange(
                        CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B, CANONICAL_OP_UPSERT, 1L,
                        canonicalWorkoutRecord(
                            id = CANONICAL_WORKOUT_B,
                            revision = 1L,
                            exercises = canonicalJsonArray(canonicalWorkoutEntry(notes = "li\nne")),
                        ),
                    ),
                ),
            ),
        )
        val result = canonicalStore(database).applyBatch(batch, 0L)
        assertEquals(2, result.appliedRoots)
        assertEquals(0, result.ignoredRoots)
        assertEquals(
            "1e400",
            JSONObject(
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!,
            ).getString("name"),
        )
        assertEquals(
            "li\nne",
            JSONObject(
                database.syncCanonicalDao()
                    .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!.canonicalJson!!,
            ).getJSONArray("exercises").getJSONObject(0).getString("notes"),
        )
        val beforeA = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson!!
        val beforeB = database.syncCanonicalDao()
            .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!.canonicalJson!!
        val replay = canonicalStore(database).applyBatch(batch, 0L)
        assertEquals(0, replay.appliedRoots)
        assertEquals(2, replay.ignoredRoots)
        assertEquals(
            beforeA,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_A)!!.canonicalJson,
        )
        assertEquals(
            beforeB,
            database.syncCanonicalDao()
                .getRoot(CANONICAL_ACCOUNT, CANONICAL_TYPE_WORKOUT, CANONICAL_WORKOUT_B)!!.canonicalJson,
        )
    }
}
