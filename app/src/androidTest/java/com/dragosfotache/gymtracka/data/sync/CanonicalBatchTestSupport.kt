package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import org.json.JSONArray
import org.json.JSONObject

/**
 * R05b independent QA fixtures. Hand-written from docs/remaining-work/R05b-task.md
 * and the shared backend contract OpenAPI wire shapes only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle. Stored canonical bodies are asserted field by
 * field with independent JSON access.
 */
internal const val R05B_ACCOUNT = "account-a"
internal const val R05B_OTHER_ACCOUNT = "account-b"
internal const val R05B_GENERATION = 7L

internal const val R05B_WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
internal const val R05B_WORKOUT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
internal const val R05B_WORKOUT_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
internal const val R05B_ROUTINE_A = "dddddddd-dddd-dddd-dddd-dddddddddddd"
internal const val R05B_ROUTINE_B = "dddddddd-dddd-dddd-dddd-ddddddddddde"
internal const val R05B_CUSTOM_A = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
internal const val R05B_CUSTOM_B = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeef"
internal const val R05B_ENTRY_A1 = "11111111-1111-1111-1111-111111111111"
internal const val R05B_ENTRY_A2 = "22222222-2222-2222-2222-222222222222"
internal const val R05B_SET_A1 = "33333333-3333-3333-3333-333333333333"
internal const val R05B_SET_A2 = "44444444-4444-4444-4444-444444444444"
internal const val R05B_SET_A3 = "55555555-5555-5555-5555-555555555555"
internal const val R05B_EXERCISE_REF = "ffffffff-ffff-ffff-ffff-ffffffffffff"

internal fun r05bMuscleRef(id: String, name: String, type: String): JSONObject =
    JSONObject().put("id", id).put("name", name).put("type", type)

internal fun r05bEquipmentRef(id: String, name: String, numItems: Int?): JSONObject =
    JSONObject().put("id", id).put("name", name).put("numItems", numItems ?: JSONObject.NULL)
internal const val R05B_EXERCISE_REF_2 = "ffffffff-ffff-ffff-ffff-fffffffffffe"

internal const val R05B_BATCH_1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
internal const val R05B_BATCH_2 = "11111111-2222-3333-4444-555555555555"
internal const val R05B_CURSOR_1 = "cursor-1"
internal const val R05B_CURSOR_2 = "cursor-2"

internal const val R05B_STARTED_AT = "2026-09-24T14:00:00Z"
internal const val R05B_FINISHED_AT = "2026-09-24T15:02:12Z"
internal const val R05B_MILLIS_AT = "2026-09-24T14:00:00.123Z"
internal const val R05B_EARLIER_AT = "2026-09-24T13:00:00Z"

internal const val R05B_TYPE_WORKOUT = "workout"
internal const val R05B_TYPE_ROUTINE = "routine"
internal const val R05B_TYPE_CUSTOM = "customExercise"
internal const val R05B_OP_UPSERT = "upsert"
internal const val R05B_OP_DELETE = "delete"

internal fun r05bJsonArray(vararg items: JSONObject): JSONArray {
    val array = JSONArray()
    items.forEach { array.put(it) }
    return array
}

internal fun r05bWorkoutSet(
    id: String = R05B_SET_A1,
    position: Int = 0,
    repetitions: Int? = 8,
    weightKg: Double? = 50.0,
    isCompleted: Boolean = false,
    restSeconds: Int? = null,
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("position", position)
        .put("repetitions", repetitions ?: JSONObject.NULL)
        .put("weightKg", weightKg ?: JSONObject.NULL)
        .put("isCompleted", isCompleted)
        .put("restSeconds", restSeconds ?: JSONObject.NULL)

internal fun r05bWorkoutEntry(
    id: String = R05B_ENTRY_A1,
    exerciseId: String = R05B_EXERCISE_REF,
    exerciseName: String = "Press",
    position: Int = 0,
    notes: String = "",
    restDurationSeconds: Int = 30,
    sets: JSONArray = JSONArray(),
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("exerciseId", exerciseId)
        .put("exerciseName", exerciseName)
        .put("position", position)
        .put("notes", notes)
        .put("restDurationSeconds", restDurationSeconds)
        .put("sets", sets)

internal fun r05bWorkoutRecord(
    id: String = R05B_WORKOUT_A,
    name: String = "W",
    status: String = "active",
    startedAt: String = R05B_STARTED_AT,
    finishedAt: String? = null,
    revision: Long = 1L,
    exercises: JSONArray = JSONArray(),
    totalVolumeKg: Double = 0.0,
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("status", status)
        .put("startedAt", startedAt)
        .put("finishedAt", finishedAt ?: JSONObject.NULL)
        .put("revision", revision)
        .put("exercises", exercises)
        .put("totalVolumeKg", totalVolumeKg)

internal fun r05bRoutineSet(
    id: String = R05B_SET_A1,
    position: Int = 0,
    repetitions: Int? = 8,
    weightKg: Double? = 50.0,
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("position", position)
        .put("repetitions", repetitions ?: JSONObject.NULL)
        .put("weightKg", weightKg ?: JSONObject.NULL)

internal fun r05bRoutineEntry(
    id: String = R05B_ENTRY_A1,
    exerciseId: String = R05B_EXERCISE_REF,
    exerciseName: String = "Press",
    position: Int = 0,
    restDurationSeconds: Int = 30,
    sets: JSONArray = JSONArray(),
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("exerciseId", exerciseId)
        .put("exerciseName", exerciseName)
        .put("position", position)
        .put("restDurationSeconds", restDurationSeconds)
        .put("sets", sets)

internal fun r05bRoutineRecord(
    id: String = R05B_ROUTINE_A,
    name: String = "R",
    isDraft: Boolean = true,
    revision: Long = 1L,
    exercises: JSONArray = JSONArray(),
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("isDraft", isDraft)
        .put("revision", revision)
        .put("exercises", exercises)

internal fun r05bCustomRecord(
    id: String = R05B_CUSTOM_A,
    name: String = "Custom",
    revision: Long = 1L,
    category: String? = null,
    difficultyLevel: String? = null,
    forceType: String? = null,
    mechanics: String? = null,
    instructions: JSONArray? = null,
    muscleGroups: JSONArray = JSONArray(),
    equipment: JSONArray = JSONArray(),
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", "reps")
        .put("category", category ?: JSONObject.NULL)
        .put("difficultyLevel", difficultyLevel ?: JSONObject.NULL)
        .put("forceType", forceType ?: JSONObject.NULL)
        .put("mechanics", mechanics ?: JSONObject.NULL)
        .put("instructions", instructions ?: JSONObject.NULL)
        .put("isUserCreated", true)
        .put("revision", revision)
        .put("muscleGroups", muscleGroups)
        .put("equipment", equipment)

internal fun r05bChange(
    aggregateType: String,
    aggregateId: String,
    operation: String,
    revision: Long,
    record: JSONObject?,
): JSONObject =
    JSONObject()
        .put("aggregateType", aggregateType)
        .put("aggregateId", aggregateId)
        .put("operation", operation)
        .put("revision", revision)
        .put("record", record ?: JSONObject.NULL)

internal fun r05bPull(
    batchId: String = R05B_BATCH_1,
    cursor: String = R05B_CURSOR_1,
    changes: JSONArray,
): String =
    JSONObject()
        .put(
            "batches",
            JSONArray().put(
                JSONObject()
                    .put("batchId", batchId)
                    .put("cursor", cursor)
                    .put("changes", changes),
            ),
        )
        .put("nextCursor", cursor)
        .put("hasMore", false)
        .toString()

internal fun r05bDecodeBatch(json: String): SyncBatchModel =
    decodePullResult(json).batches.single()

internal fun r05bStore(
    database: GymDatabase,
    session: RepositorySession = RepositorySession(R05B_ACCOUNT, R05B_GENERATION),
): CanonicalStore = CanonicalStore(database, session)

internal suspend fun GymDatabase.r05bSeedDevice(
    account: String? = R05B_ACCOUNT,
    generation: Long = R05B_GENERATION,
) {
    syncDao().upsertDeviceState(SyncDeviceStateEntity(activeAccountId = account, sessionGeneration = generation))
}

internal fun GymDatabase.r05bSeedCanonicalRow(
    accountId: String,
    aggregateType: String,
    aggregateId: String,
    serverRevision: Long?,
    canonicalState: String,
    canonicalJson: String?,
    watermark: Long,
    cacheEpoch: Long,
) {
    openHelper.writableDatabase.execSQL(
        """INSERT INTO sync_canonical_roots
            (account_id, aggregate_type, aggregate_id, server_revision,
             canonical_state, canonical_json, local_generation_watermark, cache_epoch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        arrayOf<Any?>(
            accountId, aggregateType, aggregateId, serverRevision,
            canonicalState, canonicalJson, watermark, cacheEpoch,
        ),
    )
}

internal fun GymDatabase.r05bSeedCacheRow(
    accountId: String,
    baselineComplete: Boolean,
    cacheEpoch: Long,
    projectionDirty: Boolean,
    materializedEpoch: Long,
) {
    openHelper.writableDatabase.execSQL(
        """INSERT INTO sync_account_cache
            (account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch)
            VALUES (?, ?, ?, ?, ?)""",
        arrayOf<Any?>(
            accountId, if (baselineComplete) 1 else 0, cacheEpoch,
            if (projectionDirty) 1 else 0, materializedEpoch,
        ),
    )
}

internal fun GymDatabase.r05bSyncStateCursor(account: String): String? =
    openHelper.writableDatabase.query(
        "SELECT cursor FROM sync_state WHERE account_id = ?",
        arrayOf(account),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

internal fun GymDatabase.r05bCanonicalCount(): Int =
    openHelper.writableDatabase.query("SELECT COUNT(*) FROM sync_canonical_roots").use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

internal fun GymDatabase.r05bCacheCount(): Int =
    openHelper.writableDatabase.query("SELECT COUNT(*) FROM sync_account_cache").use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
