package com.dragosfotache.gymtracka.data.sync.roombackend

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymRepository
import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.claimSeedDevice
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessTokenProvider
import com.dragosfotache.gymtracka.data.sync.http.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.http.OkHttpServiceTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTimeouts
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.http.newServiceHttpClient
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import org.junit.Assert.assertTrue

/**
 * R10a independent QA harness. Hand-written from docs/archive/remaining-work/R10a-task.md
 * (normative packet) and coordinator-supplied source-independent facts only.
 *
 * No production source inspected. Every domain expectation in the scenario files
 * is an independent literal; production encoder output is never used as an oracle.
 * Scripted [GymSyncApi] wrappers below may gate or fail real responses but never
 * synthesize successful domain data: success paths always delegate to the
 * delivered production [HttpGymSyncApi] over real verified TLS HTTP.
 *
 * Engine loop packages/signatures follow the archived composition contract and
 * its source-independent signature/schema appendix.
 */
internal const val R10A_ARG_PORT = "roomBackendPort"
internal const val R10A_ARG_CERT = "roomBackendCertificate"
internal const val R10A_SESSION_GENERATION = 1L

internal data class R10aInputs(val port: Int, val certificateBase64: String)

internal data class R10aAccount(val accountId: String, val accessToken: String) {
    override fun toString(): String = "R10aAccount(redacted)"
}

/** Explicit external gate: MUST fail when relay inputs are absent, never silently skip. */
internal fun r10aRequireInputs(): R10aInputs {
    val args = InstrumentationRegistry.getArguments()
    val portRaw = requireNotNull(args.getString(R10A_ARG_PORT)) {
        "R10a external gate requires instrumentation arg '$R10A_ARG_PORT'"
    }
    val cert = requireNotNull(args.getString(R10A_ARG_CERT)) {
        "R10a external gate requires instrumentation arg '$R10A_ARG_CERT'"
    }
    val port = requireNotNull(portRaw.toIntOrNull()) {
        "R10a external gate requires numeric '$R10A_ARG_PORT'"
    }
    require(cert.isNotBlank()) { "R10a external gate requires non-blank '$R10A_ARG_CERT'" }
    return R10aInputs(port, cert)
}

internal data class R10aTransport(
    val origin: ServiceOrigin,
    val client: OkHttpClient,
    val transport: OkHttpServiceTransport,
)

/** Normal verified-TLS stack per spec; hostname verification/TLS trust are never disabled. */
internal fun r10aNewTransport(inputs: R10aInputs): R10aTransport {
    val der = Base64.decode(inputs.certificateBase64, Base64.DEFAULT)
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    val handshake = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate)
        .build()
    val builder = OkHttpClient.Builder()
        .sslSocketFactory(handshake.sslSocketFactory(), handshake.trustManager)
    // Bounded read timeout: the lost-response control withholds the downstream
    // response until the normal client timeout, yielding retryable uncertainty.
    val timeouts = ServiceHttpTimeouts(readMillis = 2_000L)
    val client = newServiceHttpClient(timeouts = timeouts, builder = builder)
    val origin = ServiceOrigin.parse("https://localhost:${inputs.port}")
    return R10aTransport(origin, client, OkHttpServiceTransport(origin, client))
}

internal fun r10aNewApi(transport: R10aTransport, account: R10aAccount): HttpGymSyncApi {
    val binding = ServiceSessionBinding(transport.origin, account.accountId, R10A_SESSION_GENERATION)
    val boundToken = BoundServiceAccessToken(binding, OpaqueToken(account.accessToken))
    val provider = BoundServiceAccessTokenProvider { boundToken }
    return HttpGymSyncApi(binding = binding, tokenProvider = provider, transport = transport.transport)
}

/**
 * Test control calls over the same trusted TLS origin. Secret-bearing setup JSON
 * is never dumped on failure.
 */
internal suspend fun r10aPostControl(
    transport: R10aTransport,
    port: Int,
    path: String,
    bearer: String?,
): String = withContext(Dispatchers.IO) {
    val requestBuilder = Request.Builder()
        .url("https://localhost:$port$path")
        .post(ByteArray(0).toRequestBody(null))
    if (bearer != null) requestBuilder.header("Authorization", "Bearer $bearer")
    transport.client.newCall(requestBuilder.build()).execute().use { response ->
        val body = response.body.string()
        check(response.isSuccessful) {
            "R10a control $path failed with HTTP ${response.code}"
        }
        body
    }
}

internal suspend fun r10aMintAccount(transport: R10aTransport, port: Int): R10aAccount {
    val body = r10aPostControl(transport, port, "/__r10__/account", bearer = null)
    val json = JSONObject(body)
    return R10aAccount(
        accountId = json.getString("accountId"),
        accessToken = json.getString("accessToken"),
    )
}

internal suspend fun r10aLoseNextPush(transport: R10aTransport, port: Int, account: R10aAccount) {
    r10aPostControl(transport, port, "/__r10__/lose-next-push", bearer = account.accessToken)
}

internal data class R10aStore(
    val database: GymDatabase,
    val session: RepositorySession,
    val owner: PushRunOwner,
    val fileName: String,
) {
    fun repository(): GymRepository = GymRepository(database, session)
}

/** Isolated file-backed Room store; no asset needed when the graph references only created customs. */
internal suspend fun r10aOpenStore(context: Context, base: String, accountId: String): R10aStore {
    val fileName = "r10a-$base-${UUID.randomUUID()}.db"
    val database = Room.databaseBuilder(context, GymDatabase::class.java, fileName).build()
    database.claimSeedDevice(accountId, R10A_SESSION_GENERATION)
    return R10aStore(
        database = database,
        session = RepositorySession(accountId, R10A_SESSION_GENERATION),
        owner = PushRunOwner(),
        fileName = fileName,
    )
}

internal fun r10aCloseAndDelete(context: Context, store: R10aStore) {
    try {
        store.database.close()
    } finally {
        context.deleteDatabase(store.fileName)
    }
}

/** Controllable test lease: tracks valid/open, closes exactly once, never touches Room.
 * Close invalidates the lease and rejects a second close; never reuse across publications. */
internal class R10aTestLease : ProjectionPublicationLease {
    var closeCount = 0
        private set
    var valid = true
        private set

    fun revoke() {
        valid = false
    }

    override fun checkValid() {
        if (!valid) throw ProjectionLeaseInvalidException()
    }

    override fun close() {
        check(closeCount == 0) { "R10a test lease closed twice" }
        valid = false
        closeCount++
    }
}

internal class R10aRecordingBarrier(
    private val lease: R10aTestLease,
    private val onAcquire: (suspend (ProjectionPublicationRequest) -> Unit)? = null,
) : ProjectionPublicationBarrier {
    val requests = mutableListOf<ProjectionPublicationRequest>()

    override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease {
        requests.add(request)
        onAcquire?.invoke(request)
        return lease
    }
}

/** Delegating wrapper: fails push with a typed offline error; other paths delegate to the real API. */
internal class R10aOfflinePushApi(private val real: GymSyncApi) : GymSyncApi {
    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        throw SyncApiException.Transient()
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel = real.pull(cursor, limit)

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel =
        real.snapshot(pageToken, limit)
}

/** Delegating wrapper: gates the real pull response behind [gate]; newer local edits land while pending. */
internal class R10aGatePullApi(
    private val real: GymSyncApi,
    private val gate: CompletableDeferred<Unit>,
) : GymSyncApi {
    val pullEntered = CompletableDeferred<Unit>()
    val pullCalls = mutableListOf<Pair<String?, Int>>()

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel = real.push(requests)

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        pullCalls += cursor to limit
        val actual = real.pull(cursor, limit)
        pullEntered.complete(Unit)
        gate.await()
        return actual
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel =
        real.snapshot(pageToken, limit)
}

/** Delegating wrapper: fails one pull with explicit retention expiry; never synthesizes data. */
internal class R10aExpireOncePullApi(private val real: GymSyncApi) : GymSyncApi {
    var expired = false
        private set

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel = real.push(requests)

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        if (!expired) {
            expired = true
            throw SyncApiException.CursorExpired()
        }
        return real.pull(cursor, limit)
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel =
        real.snapshot(pageToken, limit)
}

/** Delegating wrapper: fails the Nth snapshot page deterministically; never synthesizes domain pages. */
internal class R10aFailOnceSnapshotApi(
    private val real: GymSyncApi,
    private val failure: SyncApiException = SyncApiException.Transient(),
    private val failOnCall: Int = 1,
) : GymSyncApi {
    var snapshotCalls = 0
        private set
    var failed = false
        private set

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel = real.push(requests)

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel = real.pull(cursor, limit)

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        snapshotCalls += 1
        if (!failed && snapshotCalls == failOnCall) {
            failed = true
            throw failure
        }
        return real.snapshot(pageToken, limit)
    }
}

internal data class R10aCacheRow(
    val baselineComplete: Boolean,
    val cacheEpoch: Long,
    val materializedEpoch: Long,
    val projectionDirty: Boolean,
)

internal fun GymDatabase.r10aReadCache(accountId: String): R10aCacheRow? {
    openHelper.readableDatabase.query(
        "SELECT baseline_complete, cache_epoch, materialized_epoch, projection_dirty" +
            " FROM sync_account_cache WHERE account_id = ?",
        arrayOf(accountId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        return R10aCacheRow(
            baselineComplete = cursor.getLong(0) == 1L,
            cacheEpoch = cursor.getLong(1),
            materializedEpoch = cursor.getLong(2),
            projectionDirty = cursor.getLong(3) == 1L,
        )
    }
}

internal fun r10aAssertOutboxEmpty(database: GymDatabase, accountId: String, label: String) {
    val count = database.r10aCount(
        "SELECT COUNT(*) FROM sync_outbox WHERE account_id = ?",
        arrayOf(accountId),
    )
    assertTrue("$label expected empty outbox, got $count rows", count == 0)
}

/** Reopen the same file-backed store without reseeding; a new owner simulates a fresh process. */
internal fun r10aReopen(context: Context, store: R10aStore): R10aStore {
    store.database.close()
    val database = Room.databaseBuilder(context, GymDatabase::class.java, store.fileName).build()
    return store.copy(database = database, owner = PushRunOwner())
}

internal data class R10aOutboxRow(
    val sequence: Long,
    val mutationId: String,
    val payloadVersion: Long,
    val payloadJson: String,
    val localGeneration: Long,
    val state: String,
    val attemptCount: Long,
    val retryAfterMillis: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

/** Exact frozen outbox evidence: sequence/mutation ID/payload version/wrapper UTF-8/generation/state. */
internal fun GymDatabase.r10aOutboxRows(accountId: String): List<R10aOutboxRow> {
    openHelper.readableDatabase.query(
        "SELECT sequence, mutation_id, payload_version, payload_json, local_generation, state," +
            " attempt_count, retry_after_millis, error_code, error_message" +
            " FROM sync_outbox WHERE account_id = ? ORDER BY sequence",
        arrayOf(accountId),
    ).use { cursor ->
        val rows = mutableListOf<R10aOutboxRow>()
        while (cursor.moveToNext()) {
            rows += R10aOutboxRow(
                sequence = cursor.getLong(0),
                mutationId = cursor.getString(1),
                payloadVersion = cursor.getLong(2),
                payloadJson = cursor.getString(3),
                localGeneration = cursor.getLong(4),
                state = cursor.getString(5),
                attemptCount = cursor.getLong(6),
                retryAfterMillis = if (cursor.isNull(7)) null else cursor.getLong(7),
                errorCode = if (cursor.isNull(8)) null else cursor.getString(8),
                errorMessage = if (cursor.isNull(9)) null else cursor.getString(9),
            )
        }
        return rows
    }
}

internal data class R10aSyncStateRow(val cursor: String?, val lastSuccessMillis: Long?)

internal fun GymDatabase.r10aReadSyncState(accountId: String): R10aSyncStateRow? {
    openHelper.readableDatabase.query(
        "SELECT cursor, last_success_millis FROM sync_state WHERE account_id = ?",
        arrayOf(accountId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        return R10aSyncStateRow(
            cursor = if (cursor.isNull(0)) null else cursor.getString(0),
            lastSuccessMillis = if (cursor.isNull(1)) null else cursor.getLong(1),
        )
    }
}

internal data class R10aCanonicalRow(val serverRevision: Long?, val canonicalState: String)

internal fun GymDatabase.r10aReadCanonical(
    accountId: String,
    aggregateType: String,
    aggregateId: String,
): R10aCanonicalRow? {
    openHelper.readableDatabase.query(
        "SELECT server_revision, canonical_state FROM sync_canonical_roots" +
            " WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
        arrayOf(accountId, aggregateType, aggregateId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        return R10aCanonicalRow(
            serverRevision = if (cursor.isNull(0)) null else cursor.getLong(0),
            canonicalState = cursor.getString(1),
        )
    }
}

internal fun GymDatabase.r10aCount(sql: String, args: Array<Any?> = emptyArray()): Int {
    openHelper.readableDatabase.query(sql, args).use { cursor ->
        cursor.moveToFirst()
        return cursor.getInt(0)
    }
}

internal fun GymDatabase.r10aStageCount(accountId: String): Int =
    r10aCount("SELECT COUNT(*) FROM sync_snapshot_stages WHERE account_id = ?", arrayOf(accountId))

internal fun r10aDurableEpoch(database: GymDatabase, accountId: String): Long =
    database.r10aReadCache(accountId)?.cacheEpoch ?: 0L

/** Frozen-head comparison: immutable wrapper fields unchanged; head index 0 must read
 * state "frozen" (previously pending or already frozen) while untouched successors keep
 * their old state. error_code/error_message are outcome bookkeeping, recorded but not
 * frozen-compared; attempt_count joins the exact post-failure/reopen retention snapshot. */
internal fun r10aAssertOutboxFrozen(before: List<R10aOutboxRow>, after: List<R10aOutboxRow>, label: String) {
    assertTrue("$label expected ${before.size} retained rows, got ${after.size}", before.size == after.size)
    before.zip(after).forEachIndexed { index, (oldRow, newRow) ->
        assertTrue("$label row $index sequence unchanged", oldRow.sequence == newRow.sequence)
        assertTrue("$label row $index mutation ID unchanged", oldRow.mutationId == newRow.mutationId)
        assertTrue("$label row $index payload version unchanged", oldRow.payloadVersion == newRow.payloadVersion)
        assertTrue("$label row $index wrapper bytes unchanged", oldRow.payloadJson == newRow.payloadJson)
        assertTrue(
            "$label row $index generation unchanged",
            oldRow.localGeneration == newRow.localGeneration,
        )
        if (index == 0) {
            assertTrue(
                "$label head was pending or frozen, got [${oldRow.state}]",
                oldRow.state == "pending" || oldRow.state == "frozen",
            )
            assertTrue("$label head state frozen, got [${newRow.state}]", newRow.state == "frozen")
        } else {
            assertTrue(
                "$label row $index successor state retained [${newRow.state}]",
                newRow.state == oldRow.state,
            )
        }
    }
}

internal fun GymDatabase.r10aOutboxRetryAfterMax(accountId: String): Long? {
    openHelper.readableDatabase.query(
        "SELECT MAX(retry_after_millis) FROM sync_outbox WHERE account_id = ?",
        arrayOf(accountId),
    ).use { cursor ->
        cursor.moveToFirst()
        return if (cursor.isNull(0)) null else cursor.getLong(0)
    }
}

internal fun GymDatabase.r10aReadCanonicalJson(
    accountId: String,
    aggregateType: String,
    aggregateId: String,
): String? {
    openHelper.readableDatabase.query(
        "SELECT canonical_json FROM sync_canonical_roots" +
            " WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
        arrayOf(accountId, aggregateType, aggregateId),
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) return null
        return cursor.getString(0)
    }
}

/** Mutable injected clock for deterministic backoff tests; no wall-clock waits. */
internal class R10aMutableNow(var current: Long) {
    fun get(): Long = current
}

internal fun R10aMutableNow.advanceBeyond(retryAfterMillis: Long?) {
    if (retryAfterMillis != null && retryAfterMillis >= current) {
        current = retryAfterMillis + 1L
    }
}

/** Delegating wrapper: records real push outcomes for duplicate verification.
 * Never synthesizes domain data; every result comes from the real transport. */
internal class R10aRecordingPushApi(private val real: GymSyncApi) : GymSyncApi {
    val pushResults = mutableListOf<PushResultModel>()

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel =
        real.push(requests).also { pushResults += it }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel = real.pull(cursor, limit)

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel =
        real.snapshot(pageToken, limit)
}
internal fun r10aAssertBaselineComplete(database: GymDatabase, accountId: String, label: String): R10aCacheRow {
    val cache = checkNotNull(database.r10aReadCache(accountId)) {
        "$label expected account cache row"
    }
    assertTrue("$label expected complete baseline", cache.baselineComplete)
    assertTrue("$label expected positive cache epoch, got ${cache.cacheEpoch}", cache.cacheEpoch > 0)
    return cache
}
