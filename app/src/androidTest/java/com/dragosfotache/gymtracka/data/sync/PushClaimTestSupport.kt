package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncDeviceStateEntity
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.PushStore
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import com.dragosfotache.gymtracka.data.sync.store.SyncStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * R06a independent QA fixtures. Hand-written from docs/archive/remaining-work/R06a-task.md,
 * the coordinator's API/harness facts and the shared backend contract only.
 *
 * Every expected value below is a literal authored here; no production encoder
 * output is used as an oracle. Valid durable rows are produced through the
 * existing test-owned [SyncStore] capture path; byte-exactness is asserted by
 * comparing stored bytes before and after claim/replay, never by regenerating
 * bytes with production codecs.
 */

internal const val CLAIM_ACCOUNT_A = "account-a"
internal const val CLAIM_ACCOUNT_B = "account-b"
internal const val CLAIM_GENERATION = 7L
internal const val CLAIM_STARTED_MILLIS = 1_790_258_400_000L
internal const val CLAIM_NOW_MILLIS = 1_790_258_500_000L

internal const val CLAIM_WORKOUT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
internal const val CLAIM_WORKOUT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
internal const val CLAIM_WORKOUT_OLD = "cccccccc-cccc-cccc-cccc-cccccccccccc"

internal const val CLAIM_MUTATION_1 = "11111111-1111-1111-1111-111111111111"
internal const val CLAIM_MUTATION_2 = "22222222-2222-2222-2222-222222222222"
internal const val CLAIM_MUTATION_3 = "33333333-3333-3333-3333-333333333333"
internal const val CLAIM_MUTATION_4 = "44444444-4444-4444-4444-444444444444"
internal const val CLAIM_MUTATION_5 = "55555555-5555-5555-5555-555555555555"
internal const val CLAIM_MUTATION_6 = "66666666-6666-6666-6666-666666666666"

internal fun claimSession(
    account: String? = CLAIM_ACCOUNT_A,
    generation: Long = CLAIM_GENERATION,
): RepositorySession = RepositorySession(account, generation)

internal fun claimOwner(): PushRunOwner = PushRunOwner()

internal fun GymDatabase.claimPushStore(
    session: RepositorySession = claimSession(),
    owner: PushRunOwner = claimOwner(),
): PushStore = PushStore(this, session, owner)

internal suspend fun GymDatabase.claimSeedDevice(
    account: String? = CLAIM_ACCOUNT_A,
    generation: Long = CLAIM_GENERATION,
) {
    syncDao().upsertDeviceState(
        SyncDeviceStateEntity(activeAccountId = account, sessionGeneration = generation),
    )
}

internal fun GymDatabase.claimSeedCache(
    accountId: String,
    cacheEpoch: Long,
    baselineComplete: Boolean = true,
    projectionDirty: Boolean = false,
    materializedEpoch: Long = 0L,
) {
    openHelper.writableDatabase.execSQL(
        "INSERT INTO sync_account_cache " +
            "(account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch) " +
            "VALUES (?, ?, ?, ?, ?)",
        arrayOf<Any?>(
            accountId,
            if (baselineComplete) 1 else 0,
            cacheEpoch,
            if (projectionDirty) 1 else 0,
            materializedEpoch,
        ),
    )
}

internal fun GymDatabase.claimUpdateCache(
    accountId: String,
    cacheEpoch: Long,
    materializedEpoch: Long = 0L,
) {
    openHelper.writableDatabase.execSQL(
        "UPDATE sync_account_cache SET cache_epoch = ?, materialized_epoch = ? WHERE account_id = ?",
        arrayOf<Any?>(cacheEpoch, materializedEpoch, accountId),
    )
}

internal fun GymDatabase.claimExec(sql: String, args: Array<Any?> = emptyArray()) {
    openHelper.writableDatabase.execSQL(sql, args)
}

internal suspend fun GymDatabase.claimSeedWorkout(
    id: String,
    account: String?,
    name: String = "Claimed Workout",
    generation: Long = 0L,
) {
    workoutDao().insertWorkout(
        WorkoutEntity(
            id = id,
            name = name,
            startedAtMillis = CLAIM_STARTED_MILLIS,
            accountId = account,
            serverRevision = 5L,
            localGeneration = generation,
        ),
    )
}

internal fun GymDatabase.claimCaptureStore(
    session: RepositorySession,
    ids: MutableList<String>,
): SyncStore = SyncStore(this, session) { ids.removeFirst() }

internal suspend fun GymDatabase.claimCaptureStart(
    store: SyncStore,
    account: String,
    rootId: String,
    name: String = "Claimed Workout",
    extraRoots: List<ExtraAffectedRoot> = emptyList(),
): SyncOutboxEntity {
    val outcome = store.withFencedTransaction { transaction ->
        transaction.captureEdit(
            AggregateType.WORKOUT,
            rootId,
            MutationKind.WORKOUT_START,
            changed = true,
            extraRoots = extraRoots,
        ) {
            MutationPayload.WorkoutStart(rootId, name, CLAIM_STARTED_MILLIS)
        }
    }
    checkNotNull(outcome) { "test capture must append for $rootId" }
    val mutationId = checkNotNull(outcome.request) { "test capture must carry a request" }.envelope.mutationId
    return checkNotNull(syncDao().getOutboxMutation(mutationId, account)) {
        "captured row must be readable"
    }
}

internal suspend fun GymDatabase.claimCaptureUpsert(
    store: SyncStore,
    account: String,
    rootId: String,
    name: String,
): SyncOutboxEntity {
    val outcome = store.withFencedTransaction { transaction ->
        transaction.captureEdit(
            AggregateType.WORKOUT,
            rootId,
            MutationKind.WORKOUT_UPSERT,
            changed = true,
        ) {
            MutationPayload.WorkoutUpsert(
                WorkoutInputModel(rootId, name, "active", CLAIM_STARTED_MILLIS, emptyList()),
            )
        }
    }
    checkNotNull(outcome) { "test capture must append for $rootId" }
    val mutationId = checkNotNull(outcome.request) { "test capture must carry a request" }.envelope.mutationId
    return checkNotNull(syncDao().getOutboxMutation(mutationId, account)) {
        "captured row must be readable"
    }
}

/** Inserts a duplicate payloadVersion member at the front of a valid wrapper. */
internal fun claimWrapperWithDuplicateMember(wrapper: String): String =
    wrapper.replaceFirst("{", "{\"payloadVersion\":1,")

/** Inserts a forbidden unknown member at the front of a valid wrapper. */
internal fun claimWrapperWithForbiddenField(wrapper: String): String =
    wrapper.replaceFirst("{", "{\"r06aUnknownField\":7,")

/** Re-quotes a valid wrapper with a different wrapper version. Inner bytes are untouched. */
internal fun claimWrapperWithVersion(wrapper: String, version: Int): String =
    JSONObject(wrapper).apply { put("payloadVersion", version) }.toString()

internal suspend fun GymDatabase.claimOutboxFor(account: String): List<SyncOutboxEntity> =
    syncDao().getOutboxForAccount(account)

/**
 * Deterministic complete-row snapshot of every application table for
 * no-side-effect assertions. Built from direct real SQLite reads only
 * (table list derived from sqlite_master, so no table or body can be
 * missed); no production logic and no curated column lists.
 */
internal suspend fun GymDatabase.claimSnapshot(): String {
    val db = openHelper.writableDatabase
    val tables = mutableListOf<String>()
    db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name",
    ).use { cursor ->
        while (cursor.moveToNext()) {
            tables += cursor.getString(0)
        }
    }
    return buildString {
        tables.forEach { table ->
            appendLine("[$table]")
            db.query("SELECT * FROM \"$table\" ORDER BY rowid").use { cursor ->
                appendLine(
                    "columns=" + (0 until cursor.columnCount).joinToString(",") {
                        cursor.getColumnName(it)
                    },
                )
                while (cursor.moveToNext()) {
                    appendLine(
                        (0 until cursor.columnCount).joinToString("|") { index ->
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> "null"
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { byte ->
                                    "%02x".format(byte)
                                }
                                else -> cursor.getString(index) ?: "null"
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Hand-authored wrapper shell: payloadVersion first, scrambled root order. */
internal fun claimWrapRequest(innerRequestJson: String, affectedRootsJson: String): String =
    "{\n\"payloadVersion\" : 1 ,\n\"affectedRoots\" : [ $affectedRootsJson ] ,\n" +
        "\"requestJson\" : ${JSONObject.quote(innerRequestJson)}\n}"

/** Hand-authored affected-root entry with noncanonical member order. */
internal fun claimRootEntry(aggregateId: String, localGeneration: Long): String =
    "{\"localGeneration\" : $localGeneration , \"aggregateId\" : \"$aggregateId\" , \"aggregateType\" : \"workout\"}"

internal fun GymDatabase.claimCount(sql: String): Int =
    openHelper.writableDatabase.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

/** Narrow controlled test transport: never performs production HTTP. */
internal sealed interface ClaimTransportBehavior {
    data object ReturnEmpty : ClaimTransportBehavior
    data class ReturnRejected(val errorCode: String) : ClaimTransportBehavior
    data class ThrowTransient(val retryAfterMillis: Long? = null) : ClaimTransportBehavior
    data object ThrowCancellation : ClaimTransportBehavior
    data class Gate(val gate: CompletableDeferred<Unit>, val after: ClaimTransportBehavior) : ClaimTransportBehavior
}

internal class ClaimTestTransport(
    private val database: GymDatabase,
    var behavior: ClaimTransportBehavior = ClaimTransportBehavior.ReturnEmpty,
) : GymSyncApi {
    val calls = mutableListOf<List<DurableRequestModel>>()
    var onPushEntry: (() -> Unit)? = null
    var lateResponses = 0

    private fun respond(behavior: ClaimTransportBehavior, requests: List<DurableRequestModel>): PushResultModel =
        when (behavior) {
            is ClaimTransportBehavior.ReturnEmpty ->
                decodePushResult("{\"results\":[]}")
            is ClaimTransportBehavior.ReturnRejected -> {
                val outcomes = requests.joinToString(",") { request ->
                    "{\"mutationId\":\"${request.envelope.mutationId}\"," +
                        "\"status\":\"rejected\"," +
                        "\"errorCode\":\"${behavior.errorCode}\"," +
                        "\"batch\":null}"
                }
                decodePushResult("{\"results\":[$outcomes]}")
            }
            is ClaimTransportBehavior.Gate ->
                respond(behavior.after, requests)
            is ClaimTransportBehavior.ThrowTransient ->
                throw if (behavior.retryAfterMillis == null) {
                    SyncApiException.Transient()
                } else {
                    SyncApiException.Transient(behavior.retryAfterMillis)
                }
            is ClaimTransportBehavior.ThrowCancellation ->
                throw CancellationException("claim-test transport cancellation")
        }

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        if (database.inTransaction()) {
            throw AssertionError("push transport must run outside any Room transaction")
        }
        onPushEntry?.invoke()
        calls += requests.toList()
        var current = behavior
        while (true) {
            when (val step = current) {
                is ClaimTransportBehavior.Gate -> {
                    try {
                        step.gate.await()
                    } catch (cancelled: CancellationException) {
                        // The late transport response still arrives after
                        // cancellation: synthesize and record it, then preserve
                        // cancellation identity for the caller.
                        lateResponses += 1
                        respond(step.after, requests)
                        throw cancelled
                    }
                    current = step.after
                }
                else -> return respond(step, requests)
            }
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        throw AssertionError("pull must not be called by the R06a claim harness")
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        throw AssertionError("snapshot must not be called by the R06a claim harness")
    }
}

/**
 * Adversarial test transport: shields its gate with NonCancellable so a late
 * response genuinely reaches the caller even after the caller is cancelled.
 * Never performs production HTTP.
 */
internal class ClaimAdversarialTransport(
    private val database: GymDatabase,
) : GymSyncApi {
    val gate = CompletableDeferred<Unit>()
    val calls = mutableListOf<List<DurableRequestModel>>()
    var onPushEntry: (() -> Unit)? = null
    val responseReturned = CompletableDeferred<Unit>()
    var responsesReturned = 0

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        if (database.inTransaction()) {
            throw AssertionError("push transport must run outside any Room transaction")
        }
        onPushEntry?.invoke()
        calls += requests.toList()
        return withContext(NonCancellable) {
            gate.await()
            responsesReturned += 1
            responseReturned.complete(Unit)
            decodePushResult("{\"results\":[]}")
        }
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        throw AssertionError("pull must not be called by the R06a claim harness")
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        throw AssertionError("snapshot must not be called by the R06a claim harness")
    }
}

internal fun claimFileDatabase(context: Context, name: String): GymDatabase =
    Room.databaseBuilder(context, GymDatabase::class.java, name).build()

internal fun claimUniqueDbName(base: String): String =
    "push-claim-$base-${UUID.randomUUID()}"

internal fun GymDatabase.claimBlockOutboxUpdates() {
    openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER push_claim_block_update AFTER UPDATE ON sync_outbox " +
            "BEGIN SELECT RAISE(ABORT, 'push_claim_blocked'); END",
    )
}

internal fun GymDatabase.claimDropOutboxTrigger() {
    openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS push_claim_block_update")
}
