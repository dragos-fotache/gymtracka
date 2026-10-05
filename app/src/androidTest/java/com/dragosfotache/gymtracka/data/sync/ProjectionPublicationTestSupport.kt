package com.dragosfotache.gymtracka.data.sync

import android.database.Cursor
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import org.json.JSONArray

/**
 * R05c independent QA support. Hand-written from docs/remaining-work/R05c-task.md
 * and the coordinator-supplied harness contracts only.
 *
 * No production source was inspected. Canonical JSON expectations reuse the
 * independent R05b JSON fixture builders (also handwritten); no production
 * encoder output is used as an oracle. Domain assertions use raw SQLite row
 * oracles against the exported v10 schema contract.
 */
internal const val R05C_GENERATION = 7L

internal const val R05C_MUSCLE_PRIMARY = "aaaaaaaa-0000-4000-8000-00000000c101"
internal const val R05C_MUSCLE_SECONDARY = "aaaaaaaa-0000-4000-8000-00000000c102"
internal const val R05C_EQUIPMENT = "aaaaaaaa-0000-4000-8000-00000000c103"
internal const val R05C_EQUIPMENT_2 = "aaaaaaaa-0000-4000-8000-00000000c104"
internal const val R05C_BUNDLED = "aaaaaaaa-0000-4000-8000-00000000c105"

internal const val R05C_WORKOUT_B = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaa101"
internal const val R05C_ROUTINE_B = "dddddddd-dddd-dddd-dddd-dddddddd01bb"
internal const val R05C_CUSTOM_C = "eeeeeeee-eeee-eeee-eeee-eeeeeeee01cc"
internal const val R05C_ENTRY_X1 = "11111111-1111-1111-1111-11111101cc01"
internal const val R05C_ENTRY_X2 = "22222222-2222-2222-2222-22222201cc02"
internal const val R05C_SET_X1 = "33333333-3333-3333-3333-33333301cc01"
internal const val R05C_SET_X2 = "44444444-4444-4444-4444-44444401cc02"

internal const val R05C_BATCH_3 = "33333333-3333-3333-3333-333333333333"
internal const val R05C_CURSOR_3 = "cursor-3"

/** Controllable test lease. close() never touches Room, blocks, or throws. */
internal class R05cTestLease : ProjectionPublicationLease {
    var closeCount = 0
        private set
    var valid = true
        private set

    fun revoke() {
        valid = false
    }

    override fun checkValid() {
        if (!valid) throw com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException()
    }

    override fun close() {
        closeCount++
    }
}

internal class R05cRecordingBarrier(
    private val lease: ProjectionPublicationLease,
    private val onAcquire: (suspend (ProjectionPublicationRequest) -> Unit)? = null,
) : ProjectionPublicationBarrier {
    val requests = mutableListOf<ProjectionPublicationRequest>()

    override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease {
        requests.add(request)
        onAcquire?.invoke(request)
        return lease
    }
}

internal fun r05cGrantBarrier(
    lease: ProjectionPublicationLease = R05cTestLease(),
    onAcquire: (suspend (ProjectionPublicationRequest) -> Unit)? = null,
): R05cRecordingBarrier = R05cRecordingBarrier(lease, onAcquire)

internal fun GymDatabase.r05cStore(
    account: String? = R05B_ACCOUNT,
    generation: Long = R05C_GENERATION,
    barrier: ProjectionPublicationBarrier,
): ProjectionStore = ProjectionStore(this, RepositorySession(account, generation), barrier)

internal suspend fun GymDatabase.r05cSeedDevice(
    account: String? = R05B_ACCOUNT,
    generation: Long = R05C_GENERATION,
) {
    syncDao().upsertDeviceState(
        SyncDeviceStateEntity(activeAccountId = account, cachedAccountId = account, sessionGeneration = generation),
    )
}

internal fun GymDatabase.r05cSeedCache(
    account: String,
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
            account, if (baselineComplete) 1 else 0, cacheEpoch,
            if (projectionDirty) 1 else 0, materializedEpoch,
        ),
    )
}

internal fun GymDatabase.r05cSetCache(
    account: String,
    baselineComplete: Boolean? = null,
    cacheEpoch: Long? = null,
    projectionDirty: Boolean? = null,
    materializedEpoch: Long? = null,
) {
    if (baselineComplete != null) {
        openHelper.writableDatabase.execSQL(
            "UPDATE sync_account_cache SET baseline_complete = ? WHERE account_id = ?",
            arrayOf<Any?>(if (baselineComplete) 1 else 0, account),
        )
    }
    if (cacheEpoch != null) {
        openHelper.writableDatabase.execSQL(
            "UPDATE sync_account_cache SET cache_epoch = ? WHERE account_id = ?",
            arrayOf<Any?>(cacheEpoch, account),
        )
    }
    if (projectionDirty != null) {
        openHelper.writableDatabase.execSQL(
            "UPDATE sync_account_cache SET projection_dirty = ? WHERE account_id = ?",
            arrayOf<Any?>(if (projectionDirty) 1 else 0, account),
        )
    }
    if (materializedEpoch != null) {
        openHelper.writableDatabase.execSQL(
            "UPDATE sync_account_cache SET materialized_epoch = ? WHERE account_id = ?",
            arrayOf<Any?>(materializedEpoch, account),
        )
    }
}

internal data class R05cCacheRow(
    val baselineComplete: Boolean,
    val cacheEpoch: Long,
    val projectionDirty: Boolean,
    val materializedEpoch: Long,
)

internal fun GymDatabase.r05cReadCache(account: String): R05cCacheRow? {
    val rows = r05cQuery(
        "SELECT baseline_complete, cache_epoch, projection_dirty, materialized_epoch" +
            " FROM sync_account_cache WHERE account_id = ?",
        arrayOf(account),
    )
    if (rows.isEmpty()) return null
    val row = rows.single()
    return R05cCacheRow(
        baselineComplete = (row["baseline_complete"] as Long) == 1L,
        cacheEpoch = row["cache_epoch"] as Long,
        projectionDirty = (row["projection_dirty"] as Long) == 1L,
        materializedEpoch = row["materialized_epoch"] as Long,
    )
}

internal suspend fun GymDatabase.r05cApplyCanonical(
    json: String,
    expectedEpoch: Long,
    account: String = R05B_ACCOUNT,
    generation: Long = R05C_GENERATION,
) = CanonicalStore(this, RepositorySession(account, generation)).applyBatch(r05bDecodeBatch(json), expectedEpoch)

/**
 * Seeds device identity, a baseline cache row, then applies one canonical batch
 * via the accepted CanonicalStore API (which preserves the seeded baseline and
 * marks the projection dirty). After this helper returns, publish(epoch) is
 * expected to attempt real publication work.
 */
internal suspend fun GymDatabase.r05cPreparePublication(
    changes: JSONArray,
    account: String = R05B_ACCOUNT,
    generation: Long = R05C_GENERATION,
    cacheEpoch: Long = 0L,
    materializedEpoch: Long = 0L,
    baselineComplete: Boolean = true,
    batchId: String = R05B_BATCH_1,
    cursor: String = R05B_CURSOR_1,
) {
    r05cSeedDevice(account, generation)
    r05cSeedCache(account, baselineComplete, cacheEpoch, false, materializedEpoch)
    r05cApplyCanonical(r05bPull(batchId = batchId, cursor = cursor, changes = changes), cacheEpoch, account, generation)
}

internal suspend fun GymDatabase.r05cInsertOutbox(
    mutationId: String,
    account: String = R05B_ACCOUNT,
    aggregateType: String = R05B_TYPE_WORKOUT,
    aggregateId: String = R05B_WORKOUT_A,
    state: String = "pending",
    payloadJson: String = "{\"v\":1}",
    localGeneration: Long = 1L,
    mutationType: String = "workout.upsert",
) {
    syncDao().insertOutbox(
        SyncOutboxEntity(
            mutationId = mutationId,
            accountId = account,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            mutationType = mutationType,
            localGeneration = localGeneration,
            payloadJson = payloadJson,
            state = state,
        ),
    )
}

internal fun GymDatabase.r05cQuery(sql: String, args: Array<Any?> = emptyArray()): List<Map<String, Any?>> {
    openHelper.writableDatabase.query(sql, args).use { cursor ->
        val out = mutableListOf<Map<String, Any?>>()
        while (cursor.moveToNext()) {
            val row = mutableMapOf<String, Any?>()
            for (i in 0 until cursor.columnCount) {
                row[cursor.getColumnName(i)] = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    else -> cursor.getString(i)
                }
            }
            out.add(row)
        }
        return out
    }
}

internal fun GymDatabase.r05cDump(table: String): List<String> =
    r05cQuery("SELECT * FROM $table").map { row ->
        row.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
    }.sorted()

internal fun GymDatabase.r05cDumpAll(): Map<String, List<String>> =
    listOf(
        "workouts", "workout_exercises", "workout_sets",
        "routines", "routine_exercises", "routine_sets",
        "exercises", "exercise_muscle_groups", "exercise_equipment",
        "muscle_groups", "equipment",
        "sync_outbox", "sync_state", "sync_device_state", "catalogue_state",
        "sync_canonical_roots", "sync_account_cache",
    ).associateWith { r05cDump(it) }

internal fun GymDatabase.r05cDomainCount(table: String): Int =
    (r05cQuery("SELECT COUNT(*) AS n FROM $table").single()["n"] as Long).toInt()
