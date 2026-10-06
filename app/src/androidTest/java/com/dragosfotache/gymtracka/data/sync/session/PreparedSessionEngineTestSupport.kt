package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpMethod
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpResponse
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import java.io.File
import java.security.KeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

/**
 * R09c independent QA support only. No production declarations inspected.
 *
 * Derived ONLY from docs/remaining-work/R09c-task.md concrete constructor /
 * bootstrap / result / failure contracts and its "QA harness facts" section
 * (deterministic clock, zero-push-HTTP empty sequence, exact snapshot/pull
 * method/path/query/header shapes, nonempty record/batch literals, result
 * field contracts) plus QA-owned harness facts (R09b in-memory/file builders,
 * RoomSessionAuthority read/beginPreparation/fencePrivate,
 * AndroidSessionVault.createForTesting namespace rules, test-owned cleanup,
 * singleton journal/device raw-SQL patterns; R04b ServiceAuthApi refresh-only
 * shape and ServiceHttpTransport.withResponse frame routing with
 * ServiceHttpResponse(call, response) + closeAfterConsumer; R06a
 * PushRunOwner()/RepositorySession/PushStore.withRun shapes;
 * ServiceHttpRequest query/backing-list shape from http harness QA). No
 * production source, diffs, Worker work, generated sources, decompiled
 * classes or schema JSON inspected. All literals are independently authored
 * for this slice; wire bodies are hand-written strings, never production
 * encoder output.
 */

internal object PreparedEngineFixtures {
    const val ORIGIN_ROOT = "https://sync.example.test"
    const val OTHER_ORIGIN_ROOT = "https://other.example.test"
    const val ACCOUNT = "d1e5f4a2-9b3c-4d2e-8f6a-1c2b3d4e5f60"
    const val OTHER_ACCOUNT = "e2f6a5b3-0c4d-4e3f-9a7b-2d3c4e5f6071"
    const val OP = "c3c3c3c3-c3c3-43c3-83c3-c3c3c3c3c3c3"
    const val OTHER_OP = "d4d4d4d4-d4d4-44d4-84d4-d4d4d4d4d4d4"
    const val REFRESH_KEY = "e5e5e5e5-e5e5-45e5-85e5-e5e5e5e5e5e5"
    const val ACCESS = "r09c-access-001"
    const val ACCESS_ROTATED = "r09c-access-002"
    const val REFRESH = "r09c-refresh-001"

    /** Deterministic clock keeping the QA grant (expiry 1_700_000_000_000) live. */
    const val FIXED_CLOCK = 1_699_999_900_000L

    const val STAGE_NEW = "f1f1f1f1-1111-4111-8111-111111111111"
    const val STAGE_OTHER = "f2f2f2f2-2222-4222-8222-222222222222"
    const val STAGE_RETAINED = "f3f3f3f3-3333-4333-8333-333333333333"
    const val STAGE_FRESH = "f4f4f4f4-4444-4444-8444-444444444444"
    const val STAGE_CORRUPT = "e1000004-4444-4444-8444-444444444444"
    const val PENDING_OP = "e1000005-5555-4555-8555-555555555555"

    const val OUTBOX_REJECTED_MUT = "e1000001-1111-4111-8111-111111111111"
    const val OUTBOX_NEWER_MUT = "e1000002-2222-4222-8222-222222222222"
    const val OUTBOX_FROZEN_MUT = "e1000006-6666-4666-8666-666666666666"
    const val OUTBOX_WORKOUT = "e1000003-3333-4333-8333-333333333333"

    const val BASELINE = "r09c-baseline"
    const val PAGE_2_TOKEN = "r09c-page-2"
    const val TAIL = "r09c-tail"
    const val BATCH_1 = "c1000001-3333-4333-8333-333333333333"

    const val ROUTE_A = "a1000001-1111-4111-8111-111111111111"
    const val ROUTE_B = "a1000002-2222-4222-8222-222222222222"

    const val EMPTY_SNAPSHOT_BODY =
        """{"records":[],"baselineCursor":"r09c-baseline","nextPageToken":null,"hasMore":false}"""
    const val EMPTY_PULL_BODY =
        """{"batches":[],"nextCursor":"r09c-baseline","hasMore":false}"""

    const val PENDING_BODY = """{"refreshToken":"r09c-refresh-001"}"""

    fun origin(root: String = ORIGIN_ROOT): ServiceOrigin = ServiceOrigin.parse(root)

    fun binding(
        root: String = ORIGIN_ROOT,
        accountId: String = ACCOUNT,
        generation: Long = 1L,
    ): ServiceSessionBinding =
        ServiceSessionBinding(ServiceOrigin.parse(root), accountId, generation)

    fun grant(
        accountId: String = ACCOUNT,
        access: String = ACCESS,
        refresh: String = REFRESH,
        accessExpires: Long = 1_700_000_000_000L,
    ): ServiceSession = ServiceSession(
        ServiceAccountRef(accountId, null),
        OpaqueToken(access),
        accessExpires,
        OpaqueToken(refresh),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    fun preparedRecord(
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP,
        session: ServiceSession = grant(accountId = binding.accountId),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = VaultSessionRecord(
        binding,
        operationId,
        VaultSessionPhase.PREPARED,
        session,
        pendingRefresh,
    )

    fun pendingAttempt(
        key: String = REFRESH_KEY,
        body: String = PENDING_BODY,
    ): ServiceRefreshAttempt = ServiceRefreshAttempt.fromJson(key, body)

    fun owner(): PushRunOwner = PushRunOwner()

    fun engine(
        database: GymDatabase,
        vault: SessionVault,
        auth: ServiceAuthApi,
        transport: ServiceHttpTransport,
        owner: PushRunOwner,
        origin: ServiceOrigin = origin(),
        clockMillis: () -> Long = { FIXED_CLOCK },
        uuidProvider: () -> String = DeterministicUuids(),
    ): PreparedSessionEngineCoordinator = PreparedSessionEngineCoordinator(
        database = database,
        origin = origin,
        vault = vault,
        authApi = auth,
        transport = transport,
        runOwner = owner,
        clockMillis = clockMillis,
        uuidProvider = uuidProvider,
    )

    // ---- Independent nonempty wire literals (spec shapes, own IDs/names) ----

    fun routineUpsertJson(id: String, name: String, revision: Long): String =
        """{"aggregateType":"routine","aggregateId":"$id","operation":"upsert","revision":$revision,"record":{"id":"$id","name":"$name","isDraft":true,"revision":$revision,"exercises":[]}}"""

    fun snapshotPage1(): String =
        """{"records":[${routineUpsertJson(ROUTE_A, "Old cloud draft", 1)}],""" +
            """"baselineCursor":"$BASELINE","nextPageToken":"$PAGE_2_TOKEN","hasMore":true}"""

    fun snapshotPage2(): String =
        """{"records":[${routineUpsertJson(ROUTE_B, "Second cloud draft", 1)}],""" +
            """"baselineCursor":"$BASELINE","nextPageToken":null,"hasMore":false}"""

    fun pullRevision2Terminal(): String =
        """{"batches":[{"batchId":"$BATCH_1","cursor":"$TAIL","changes":[""" +
            """${routineUpsertJson(ROUTE_A, "Changed cloud draft", 2)}]}],""" +
            """"nextCursor":"$TAIL","hasMore":false}"""

    /**
     * Transmittable durable wrapper for a seeded head mutation. Shape mirrors
     * the accepted R04b wrapper fixture (payloadVersion first, escaped inner
     * request, affected-roots array); the inner request is a hand-written
     * workout.start (OpenAPI StartWorkoutInput) matching the seeded row kind.
     * All IDs/names/times are this slice's own literals, never production
     * encoder output.
     */
    const val OUTBOX_STARTED_AT = "2026-10-06T00:00:00.000Z"

    fun durableInnerJson(mutationId: String, rootId: String, generation: Long): String =
        "{\"mutationId\":\"$mutationId\",\"type\":\"workout.start\"," +
            "\"aggregateType\":\"workout\",\"aggregateId\":\"$rootId\"," +
            "\"localGeneration\":$generation," +
            "\"payload\":{\"id\":\"$rootId\",\"name\":\"R09c\"," +
            "\"startedAt\":\"$OUTBOX_STARTED_AT\"}}"

    fun durablePayloadJson(mutationId: String, rootId: String, generation: Long): String {
        val inner = durableInnerJson(mutationId, rootId, generation)
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"payloadVersion\":1,\"requestJson\":\"$escaped\"," +
            "\"affectedRoots\":[{\"aggregateType\":\"workout\",\"aggregateId\":\"$rootId\"," +
            "\"localGeneration\":$generation}]}"
    }

    // ---- Exact actual-adapter request oracles ----

    fun assertSnapshotInitial(
        call: ServiceHttpRequest,
        limit: String = "200",
        access: String = ACCESS,
    ) {
        assertEquals(ServiceHttpMethod.GET, call.method)
        assertEquals("/v1/sync/snapshot", call.path)
        assertEquals(listOf("limit" to limit), call.query)
        assertEquals(mapOf("Authorization" to "Bearer $access"), call.headers)
        assertNull(call.body)
    }

    fun assertSnapshotContinuation(
        call: ServiceHttpRequest,
        token: String,
        limit: String = "200",
        access: String = ACCESS,
    ) {
        assertEquals(ServiceHttpMethod.GET, call.method)
        assertEquals("/v1/sync/snapshot", call.path)
        assertEquals(listOf("pageToken" to token, "limit" to limit), call.query)
        assertEquals(mapOf("Authorization" to "Bearer $access"), call.headers)
        assertNull(call.body)
    }

    fun assertPull(
        call: ServiceHttpRequest,
        cursor: String,
        limit: String = "100",
        access: String = ACCESS,
    ) {
        assertEquals(ServiceHttpMethod.GET, call.method)
        assertEquals("/v1/sync/pull", call.path)
        assertEquals(listOf("cursor" to cursor, "limit" to limit), call.query)
        assertEquals(mapOf("Authorization" to "Bearer $access"), call.headers)
        assertNull(call.body)
    }

    fun assertClosed(engineError: PreparedEngineException, secrets: List<String>) {
        org.junit.Assert.assertNull(engineError.cause)
        val message = engineError.message.orEmpty()
        org.junit.Assert.assertTrue(message.isNotEmpty())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("closed error must not echo <$secret>", message.contains(secret))
            assertFalse("closed display must not echo <$secret>", engineError.toString().contains(secret))
        }
    }

    fun assertRedacted(rendered: String, secrets: List<String>) {
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("display must be fixed/redacted but contained <$secret>", rendered.contains(secret))
        }
    }
}

/** Deterministic valid-UUID provider; avoids wall-clock/random flakiness. */
internal class DeterministicUuids(private var next: Long = 1L) : () -> String {
    override fun invoke(): String {
        val n = next++
        return "d0000000-0000-4000-8000-" + "%012x".format(n)
    }
}

/** Refresh-only scripted ServiceAuthApi. Mirrors the accepted R04b QA fake shape. */
internal class PreparedEngineAuthApi : ServiceAuthApi {
    var refreshCalls = 0
        private set
    val seenAttempts = mutableListOf<ServiceRefreshAttempt>()
    val script = mutableListOf<suspend (ServiceRefreshAttempt) -> ServiceSession>()
    var refreshGate: (suspend (ServiceRefreshAttempt) -> Unit)? = null

    fun answer(session: ServiceSession) {
        script += { _ -> session }
    }

    fun failWith(error: SyncApiException) {
        script += { _ -> throw error }
    }

    override suspend fun challenge(
        purpose: com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose,
    ): com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge {
        throw UnsupportedOperationException("R09c uses refresh only")
    }

    override suspend fun exchange(
        attempt: com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt,
    ): ServiceSession {
        throw UnsupportedOperationException("R09c uses refresh only")
    }

    override suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession {
        refreshCalls += 1
        seenAttempts += attempt
        refreshGate?.invoke(attempt)
        val step = script.getOrNull(refreshCalls - 1)
        if (step != null) return step(attempt)
        throw SyncApiException.Transient()
    }

    override suspend fun logout(refreshToken: OpaqueToken) {
        throw UnsupportedOperationException("R09c uses refresh only")
    }
}

private val preparedEngineCallClient: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }

/**
 * Scripted actual-adapter ServiceHttpTransport for androidTest. Each queued
 * frame is served once in order through the real ServiceHttpResponse path;
 * every served frame captures its immutable request snapshot. Never performs
 * production HTTP. Push Empty is local (never HTTP): queue only snapshot/pull
 * frames for empty-outbox runs.
 */
internal class PreparedEngineTransport : ServiceHttpTransport {
    data class Frame(val code: Int, val body: String)

    val queue = ArrayDeque<Frame>()
    val capturedRequests = mutableListOf<ServiceHttpRequest>()
    var responseGate: (suspend () -> Unit)? = null
    var serve: ((callIndex: Int, request: ServiceHttpRequest) -> Frame)? = null
    private var calls = 0

    fun queueOk(body: String) {
        queue.addLast(Frame(200, body))
    }

    fun queueCode(code: Int, body: String) {
        queue.addLast(Frame(code, body))
    }

    fun snapshotPaths(): List<String> = capturedRequests.map { it.path }

    override suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        responseGate?.invoke()
        capturedRequests += request
        calls += 1
        val frame = serve?.invoke(calls, request)
            ?: queue.removeFirstOrNull()
            ?: throw AssertionError("PreparedEngineTransport exhausted: no queued response")
        val httpRequest = Request.Builder()
            .url("https://sync.example.test/" + request.path.trimStart('/'))
            .build()
        val httpResponse = Response.Builder()
            .request(httpRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(frame.code)
            .message("Fixture")
            .header("Content-Type", "application/json")
            .body(frame.body.toResponseBody("application/json".toMediaType()))
            .build()
        val wire = ServiceHttpResponse(preparedEngineCallClient.newCall(httpRequest), httpResponse)
        try {
            return consume(wire)
        } finally {
            wire.closeAfterConsumer()
        }
    }
}

/** Test-owned namespace/vault/database helpers. Mirrors accepted R09b QA patterns. */
internal class PreparedEngineHarness(private val suffix: String) {
    val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "gymtracka-test-r09c-$suffix"
    private val usedDatabases = mutableListOf<String>()

    fun clean() {
        deleteNamespacePath(namespace)
        for (name in usedDatabases) {
            try {
                context.deleteDatabase(name)
            } catch (_: Exception) {
            }
        }
        usedDatabases.clear()
    }

    fun openInMemoryDb(): GymDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java).build()

    /** Create-once file DB: deletes any prior copy, then builds and tracks it. */
    fun createFileDb(name: String): GymDatabase {
        usedDatabases += name
        context.deleteDatabase(name)
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    /** Reopen WITHOUT delete: the same physical DB must survive close/reopen. */
    fun reopenFileDb(name: String): GymDatabase {
        if (!usedDatabases.contains(name)) usedDatabases += name
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    fun newVault(): SessionVault {
        deleteNamespacePath(namespace)
        return AndroidSessionVault.createForTesting(context, namespace)
    }

    fun reopenVault(): SessionVault = AndroidSessionVault.createForTesting(context, namespace)

    /**
     * Actual R09a reservation + R09b matching prepared vault on a fresh pair.
     * Returns the prepared observation for engine admission tests.
     */
    suspend fun reservePreparedPair(
        database: GymDatabase,
        vault: SessionVault,
        origin: ServiceOrigin = PreparedEngineFixtures.origin(),
        accountId: String = PreparedEngineFixtures.ACCOUNT,
        operationId: String = PreparedEngineFixtures.OP,
        grant: ServiceSession = PreparedEngineFixtures.grant(accountId = accountId),
    ): PreparedSessionObservation {
        val authority = RoomSessionAuthority(database)
        val coordinator = SessionPreparationCoordinator(origin, authority, vault)
        return coordinator.prepare(authority.read(), operationId, grant)
    }

    fun journalRow(database: GymDatabase): List<String?> {
        database.openHelper.readableDatabase.query(
            "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            check(cursor.moveToFirst()) { "journal row must exist" }
            return (0 until 6).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
    }

    fun deviceRow(database: GymDatabase): List<String?> {
        database.openHelper.readableDatabase.query(
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            check(cursor.moveToFirst()) { "device row must exist" }
            return (0 until 4).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
    }

    /** Raw durable oracles. Column names follow the vetted R09b/R06a seeding contracts. */

    fun countRows(database: GymDatabase, table: String): Long {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM \"$table\"").use { cursor ->
            check(cursor.moveToFirst()) { "count must return a row" }
            return cursor.getLong(0)
        }
    }

    fun syncState(database: GymDatabase, account: String): Pair<String?, Long?>? {
        database.openHelper.readableDatabase.query(
            "SELECT cursor, last_success_millis FROM sync_state WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val c = if (cursor.isNull(0)) null else cursor.getString(0)
            val last = if (cursor.isNull(1)) null else cursor.getLong(1)
            return c to last
        }
    }

    data class CacheRow(
        val baselineComplete: Long,
        val cacheEpoch: Long,
        val projectionDirty: Long,
        val materializedEpoch: Long,
    )

    fun accountCache(database: GymDatabase, account: String): CacheRow? {
        database.openHelper.readableDatabase.query(
            "SELECT baseline_complete, cache_epoch, projection_dirty, materialized_epoch" +
                " FROM sync_account_cache WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return CacheRow(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3))
        }
    }

    data class CanonicalRoot(
        val aggregateType: String,
        val aggregateId: String,
        val serverRevision: Long,
        val canonicalState: String?,
        val canonicalJson: String?,
    )

    fun canonicalRoots(database: GymDatabase, account: String): List<CanonicalRoot> {
        database.openHelper.readableDatabase.query(
            "SELECT aggregate_type, aggregate_id, server_revision, canonical_state, canonical_json" +
                " FROM sync_canonical_roots WHERE account_id = ? ORDER BY aggregate_id",
            arrayOf(account),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        CanonicalRoot(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getLong(2),
                            if (cursor.isNull(3)) null else cursor.getString(3),
                            if (cursor.isNull(4)) null else cursor.getString(4),
                        ),
                    )
                }
            }
        }
    }

    data class StageHeader(
        val stageId: String,
        val accountId: String?,
        val phase: String?,
        val pageCount: Long,
        val recordCount: Long,
    )

    fun stageHeader(database: GymDatabase, stageId: String): StageHeader? {
        database.openHelper.readableDatabase.query(
            "SELECT stage_id, account_id, phase, page_count, record_count" +
                " FROM sync_snapshot_stages WHERE stage_id = ?",
            arrayOf(stageId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StageHeader(
                cursor.getString(0),
                if (cursor.isNull(1)) null else cursor.getString(1),
                if (cursor.isNull(2)) null else cursor.getString(2),
                cursor.getLong(3),
                cursor.getLong(4),
            )
        }
    }

    /** Full 14-column stage row for byte-identity preservation oracles. */
    fun stageFullRow(database: GymDatabase, stageId: String): List<String?>? {
        database.openHelper.readableDatabase.query(
            "SELECT stage_id, account_id, session_generation, cache_epoch, page_limit," +
                " source_state_present, source_cursor, source_last_success_millis," +
                " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                " page_count, record_count FROM sync_snapshot_stages WHERE stage_id = ?",
            arrayOf(stageId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return (0 until 14).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
    }

    /** Seeds one outbox row; column list follows the vetted R09b seeding contract. */
    fun seedOutboxRow(
        database: GymDatabase,
        sequence: Long,
        mutationId: String,
        aggregateId: String,
        mutationType: String,
        localGeneration: Long,
        state: String,
        errorCode: String? = null,
        payloadJson: String = "{}",
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (?, ?, ?, 'workout', ?, ?, ?, 1, ?, ?, 0, NULL, ?, NULL)",
            arrayOf<Any?>(sequence, mutationId, PreparedEngineFixtures.ACCOUNT, aggregateId, mutationType, localGeneration, payloadJson, state, errorCode),
        )
    }

    /** Full outbox dump for a preservation oracle (ordered, exact cells). */
    fun outboxSnapshot(database: GymDatabase, account: String): List<List<String?>> {
        database.openHelper.readableDatabase.query(
            "SELECT sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message" +
                " FROM sync_outbox WHERE account_id = ? ORDER BY sequence",
            arrayOf(account),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add((0 until 14).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }
    }

    fun stageRecordCount(database: GymDatabase, stageId: String): Long {
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_snapshot_records WHERE stage_id = ?",
            arrayOf(stageId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "count must return a row" }
            return cursor.getLong(0)
        }
    }

    private fun isSymlinkNoFollow(path: String): Boolean {
        return try {
            val stat = Os.lstat(path)
            (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
        } catch (_: Exception) {
            false
        }
    }

    private fun removeLinkNoFollow(path: String) {
        try {
            if (isSymlinkNoFollow(path)) Os.remove(path)
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursivelyOwned(root: File) {
        if (!root.exists() && !isSymlinkNoFollow(root.absolutePath)) return
        removeLinkNoFollow(root.absolutePath)
        if (root.isDirectory && !isSymlinkNoFollow(root.absolutePath)) {
            root.listFiles()?.forEach { deleteRecursivelyOwned(it) }
        }
        try {
            root.delete()
        } catch (_: Exception) {
        }
    }

    private fun deleteNamespacePath(ns: String) {
        val dir = File(context.noBackupFilesDir, ns)
        val vaultFile = File(dir, "session.vault")
        val tempFile = File(dir, "session.vault.new")
        removeLinkNoFollow(dir.absolutePath)
        removeLinkNoFollow(tempFile.absolutePath)
        removeLinkNoFollow(vaultFile.absolutePath)
        try {
            tempFile.delete()
            vaultFile.delete()
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { deleteRecursivelyOwned(it) }
                dir.delete()
            } else {
                dir.delete()
            }
        } catch (_: Exception) {
        }
        try {
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                .deleteEntry("com.dragosfotache.gymtracka.session.$ns")
        } catch (_: Exception) {
        }
    }
}
