package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpResponse
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationRequest
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * R09h independent QA support only. No production declarations inspected.
 *
 * Derived ONLY from docs/remaining-work/R09h-task.md (normative new-API
 * contract, refusal policy, ownership/fencing, recovery/grant, reconnect
 * rules, 8 risk obligations) plus QA-owned harness facts reused from
 * existing test-only fixtures: R04b refresh fakes, R09b in-memory/file
 * builders and RoomSessionAuthority read shapes, R09c engine auth/transport/
 * lease shapes and journal/device/raw-SQL oracle patterns, R05c lease/
 * barrier shapes, R06a PushRunOwner()/RepositorySession shapes, R07c
 * hasOutstandingLocalWork, ScopedRepositoryTest createCustomExercise
 * minimal-edit seam, and the coordinator-supplied
 * GymRepository.exerciseSummaries fenced-read seam. All literals below are
 * this slice's own valid lowercase-hex IDs/names/bodies; wire bodies are
 * hand-written strings, never production encoder output.
 */
internal object AppSessionFixtures {
    const val ORIGIN_ROOT = "https://sync.example.test"
    const val OTHER_ORIGIN_ROOT = "https://other.example.test"
    const val ACCOUNT = "a0900001-1111-4111-8111-111111111111"
    const val OTHER_ACCOUNT = "a0900002-2222-4222-8222-222222222222"
    const val OP_FRESH = "b0900001-1111-4111-8111-111111111111"
    const val OP_OTHER = "b0900002-2222-4222-8222-222222222222"
    const val OP_STALE = "b0900003-3333-4333-8333-333333333333"
    const val KEY_FRESH = "c0900001-1111-4111-8111-111111111111"
    const val STAGE = "d0900001-1111-4111-8111-111111111111"
    const val STAGE_OTHER = "d0900002-2222-4222-8222-222222222222"
    const val ACCESS = "r09h-access-001"
    const val ACCESS_ROTATED = "r09h-access-002"
    const val REFRESH = "r09h-refresh-001"
    const val REFRESH_ROTATED = "r09h-refresh-002"

    /** Deterministic clock keeping QA grants live unless a test expires them. */
    const val FIXED_CLOCK = 1_699_999_900_000L
    const val GRANT_EXPIRY = 1_700_000_000_000L

    const val BASELINE = "r09h-baseline"
    const val TAIL = "r09h-tail"
    const val BATCH_1 = "e0900001-1111-4111-8111-111111111111"
    const val BATCH_CHANGE = "e0900011-1111-4111-8111-111111111111"

    const val ROUTE_A = "f0900001-1111-4111-8111-111111111111"
    const val ROUTE_CHANGE = "f0900011-1111-4111-8111-111111111111"

    const val EMPTY_SNAPSHOT_BODY =
        """{"records":[],"baselineCursor":"r09h-baseline","nextPageToken":null,"hasMore":false}"""
    const val EMPTY_PULL_BODY =
        """{"batches":[],"nextCursor":"r09h-baseline","hasMore":false}"""
    const val TAIL_EMPTY_PULL_BODY =
        """{"batches":[],"nextCursor":"r09h-tail","hasMore":false}"""

    /**
     * Hand-written valid terminal pull carrying one routine upsert change.
     * Shape mirrors the QA-owned R09c pullRevision2Terminal literal
     * (batchId/cursor/changes/nextCursor/hasMore with an upsert record
     * carrying id/name/isDraft/revision/exercises); all IDs/names are this
     * slice's own literals, never production encoder output.
     */
    const val PULL_CHANGE_BODY =
        """{"batches":[{"batchId":"e0900011-1111-4111-8111-111111111111","cursor":"r09h-tail","changes":[""" +
            """{"aggregateType":"routine","aggregateId":"f0900011-1111-4111-8111-111111111111","operation":"upsert","revision":1,"record":""" +
            """{"id":"f0900011-1111-4111-8111-111111111111","name":"R09h Cloud Draft","isDraft":true,"revision":1,"exercises":[]}}]}],""" +
            """"nextCursor":"r09h-tail","hasMore":false}"""

    const val PENDING_BODY = """{"refreshToken":"r09h-refresh-001"}"""

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
        accessExpires: Long = GRANT_EXPIRY,
    ): ServiceSession = ServiceSession(
        ServiceAccountRef(accountId, null),
        OpaqueToken(access),
        accessExpires,
        OpaqueToken(refresh),
        GRANT_EXPIRY + 30L * 24 * 60 * 60 * 1000,
    )

    fun owner(): PushRunOwner = PushRunOwner()

    fun coordinator(
        database: GymDatabase,
        origin: ServiceOrigin?,
        vault: SessionVault,
        auth: ServiceAuthApi,
        transport: ServiceHttpTransport,
        runOwner: PushRunOwner,
        transitionBarrier: AppSessionTransitionBarrier,
        publicationBarrier: ProjectionPublicationBarrier,
        clock: CountingClock = CountingClock(),
        uuids: CountingUuids = CountingUuids(),
    ): AppSessionCoordinator = AppSessionCoordinator(
        database = database,
        origin = origin,
        vault = vault,
        authApi = auth,
        transport = transport,
        runOwner = runOwner,
        transitionBarrier = transitionBarrier,
        publicationBarrier = publicationBarrier,
        clockMillis = { clock.read() },
        uuidProvider = { uuids.next() },
    )

    fun defaultSecrets(): List<String> = listOf(
        ACCOUNT, OTHER_ACCOUNT, OP_FRESH, OP_OTHER, OP_STALE, KEY_FRESH,
        STAGE, STAGE_OTHER, ACCESS, ACCESS_ROTATED, REFRESH, REFRESH_ROTATED,
        PENDING_BODY, BASELINE, TAIL, BATCH_1, BATCH_CHANGE, ROUTE_A, ROUTE_CHANGE,
        ORIGIN_ROOT, OTHER_ORIGIN_ROOT,
    )

    fun assertRedacted(rendered: String, secrets: List<String> = defaultSecrets()) {
        assertTrue("display must be non-blank", rendered.isNotBlank())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("display must be fixed/redacted but contained <$secret>", rendered.contains(secret))
        }
    }

    fun assertClosed(error: Throwable, secrets: List<String> = defaultSecrets()) {
        assertTrue("closed error must be non-blank", error.message.orEmpty().isNotBlank())
        assertTrue("closed error must not chain causes", error.cause == null)
        assertTrue("closed error must not carry suppressed", error.suppressed.isEmpty())
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            assertFalse("closed error must not echo <$secret>", error.message.orEmpty().contains(secret))
            assertFalse("closed display must not echo <$secret>", error.toString().contains(secret))
        }
    }

    /** Tightened refusal oracle: exact AppSessionException category + preservation. */
    fun assertRefusal(
        error: Throwable,
        expected: AppSessionReason,
        secrets: List<String> = defaultSecrets(),
    ): AppSessionException {
        assertTrue("expected AppSessionException($expected) but was $error", error is AppSessionException)
        val refusal = error as AppSessionException
        assertTrue("expected reason $expected but was ${refusal.reason}", refusal.reason == expected)
        assertClosed(refusal, secrets)
        return refusal
    }

    fun assertStale(error: Throwable, secrets: List<String> = defaultSecrets()): AppSessionStaleException {
        assertTrue("expected AppSessionStaleException but was $error", error is AppSessionStaleException)
        assertClosed(error, secrets)
        return error as AppSessionStaleException
    }
}

/** Deterministic counting clock; also proves unconfigured paths do no clock I/O. */
internal class CountingClock(var now: Long = AppSessionFixtures.FIXED_CLOCK) {
    var calls = 0
        private set

    fun read(): Long {
        calls += 1
        return now
    }
}

/** Deterministic valid-UUID provider; also proves unconfigured paths do no UUID I/O. */
internal class CountingUuids(values: List<String> = defaultValues()) {
    private val iterator = values.iterator()
    var calls = 0
        private set

    fun next(): String {
        calls += 1
        return iterator.next()
    }

    companion object {
        fun defaultValues(): List<String> = listOf(
            AppSessionFixtures.OP_FRESH,
            AppSessionFixtures.KEY_FRESH,
            AppSessionFixtures.OP_OTHER,
            AppSessionFixtures.STAGE,
            AppSessionFixtures.STAGE_OTHER,
            AppSessionFixtures.OP_STALE,
            "e0900002-2222-4222-8222-222222222222",
            "e0900003-3333-4333-8333-333333333333",
        )
    }
}

/** Controllable test publication lease: close() never touches Room or throws. */
internal class AppSessionTestLease : ProjectionPublicationLease {
    var closeCount = 0
        private set
    var valid = true
        private set

    fun revoke() {
        valid = false
    }

    override fun checkValid() {
        if (!valid) throw IllegalStateException("r09h-lease-invalid")
    }

    override fun close() {
        closeCount++
    }
}

/** Scripted transition pause/drain barrier with deterministic entered/block gates. */
internal class ScriptedTransitionBarrier(
    private val lease: ProjectionPublicationLease = AppSessionTestLease(),
) : AppSessionTransitionBarrier {
    var acquires = 0
        private set
    val seenPrevious = mutableListOf<Any?>()

    /** Completed on entry to acquire so tests observe the drain before acting. */
    var enteredAcquire = CompletableDeferred<Unit>()
        private set

    fun resetEnteredAcquire() {
        enteredAcquire = CompletableDeferred<Unit>()
    }

    /** Gate invoked per acquire before serving lease/fault. */
    var acquireGate: (suspend () -> Unit)? = null

    /** One-shot faults consumed in order before serving the lease. */
    val acquireFaults = ArrayDeque<Throwable>()

    /** When true, block this acquire until [releaseAcquire] completes. */
    var blockNextAcquire = false
    val releaseAcquire = CompletableDeferred<Unit>()

    override suspend fun acquire(previous: RepositorySession?): ProjectionPublicationLease {
        acquires += 1
        seenPrevious += previous
        enteredAcquire.complete(Unit)
        acquireGate?.invoke()
        if (blockNextAcquire) {
            blockNextAcquire = false
            releaseAcquire.await()
        }
        if (acquireFaults.isNotEmpty()) throw acquireFaults.removeFirst()
        return lease
    }
}

/** Scripted publication barrier reusing the vetted R05c acquire shape. */
internal class ScriptedPublicationBarrier(
    private val lease: ProjectionPublicationLease = AppSessionTestLease(),
) : ProjectionPublicationBarrier {
    var acquires = 0
        private set
    val requests = mutableListOf<ProjectionPublicationRequest>()

    /** Gate invoked per acquire before serving lease/fault. */
    var acquireGate: (suspend (ProjectionPublicationRequest) -> Unit)? = null

    /** One-shot faults consumed in order before serving the lease. */
    val acquireFaults = ArrayDeque<Throwable>()

    override suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease {
        acquires += 1
        requests += request
        acquireGate?.invoke(request)
        if (acquireFaults.isNotEmpty()) throw acquireFaults.removeFirst()
        return lease
    }
}

/** Refresh-only scripted ServiceAuthApi mirroring the accepted R04b QA shape. */
internal class AppSessionAuthApi : ServiceAuthApi {
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

    fun cancelWith(error: kotlinx.coroutines.CancellationException) {
        script += { _ -> throw error }
    }

    override suspend fun challenge(
        purpose: com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose,
    ): com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge {
        throw UnsupportedOperationException("R09h uses refresh only")
    }

    override suspend fun exchange(
        attempt: com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt,
    ): ServiceSession {
        throw UnsupportedOperationException("R09h uses refresh only")
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
        throw UnsupportedOperationException("R09h uses refresh only")
    }
}

private val appSessionCallClient: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }

/**
 * Scripted ServiceHttpTransport for androidTest. Each queued frame is served
 * once in order through the real ServiceHttpResponse path; every served
 * frame captures its immutable request snapshot. Never performs production
 * HTTP. Wire bodies are hand-written literals, never production output.
 */
internal class AppSessionTransport : ServiceHttpTransport {
    data class Frame(val code: Int, val body: String)

    val queue = ArrayDeque<Frame>()
    val capturedRequests = mutableListOf<ServiceHttpRequest>()
    var responseGate: (suspend () -> Unit)? = null
    var serve: ((callIndex: Int, request: ServiceHttpRequest) -> Frame)? = null
    private var calls = 0

    /** Completed on entry to withResponse so tests observe admitted I/O. */
    var enteredResponse = CompletableDeferred<Unit>()
        private set

    fun resetEnteredResponse() {
        enteredResponse = CompletableDeferred<Unit>()
    }

    fun queueOk(body: String) {
        queue.addLast(Frame(200, body))
    }

    fun queueCode(code: Int, body: String) {
        queue.addLast(Frame(code, body))
    }

    override suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        responseGate?.invoke()
        capturedRequests += request
        calls += 1
        enteredResponse.complete(Unit)
        val frame = serve?.invoke(calls, request)
            ?: queue.removeFirstOrNull()
            ?: throw AssertionError("AppSessionTransport exhausted: no queued response")
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
        val wire = ServiceHttpResponse(appSessionCallClient.newCall(httpRequest), httpResponse)
        try {
            return consume(wire)
        } finally {
            wire.closeAfterConsumer()
        }
    }
}

/**
 * Transaction-executor gate proving in-flight scoped-transaction rollback
 * deterministically: the first submitted Room transaction signals entry,
 * waits for release, then runs. Uses latches (no sleeps) so real Room
 * executors stay observable under test timeouts.
 */
internal class GatedTransactionExecutor : Executor {
    private val delegate: ExecutorService = Executors.newSingleThreadExecutor()
    val enteredLatch = CountDownLatch(1)
    val releaseLatch = CountDownLatch(1)

    @Volatile
    var gateNext = false

    override fun execute(command: Runnable) {
        delegate.execute {
            if (gateNext) {
                gateNext = false
                enteredLatch.countDown()
                releaseLatch.await()
            }
            command.run()
        }
    }

    fun entered(timeoutSeconds: Long = 15L): Boolean =
        enteredLatch.await(timeoutSeconds, TimeUnit.SECONDS)

    fun shutdown() {
        delegate.shutdownNow()
    }
}

/** Test-owned namespace/vault/database helpers mirroring accepted R09b/R09c QA patterns. */
internal class AppSessionHarness(private val suffix: String) {
    val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "gymtracka-test-r09h-$suffix"
    private val fileDbs = mutableListOf<String>()
    private val gatedExecutors = mutableListOf<GatedTransactionExecutor>()

    fun clean() {
        deleteNamespacePath(namespace)
        gatedExecutors.forEach { runCatching { it.shutdown() } }
        gatedExecutors.clear()
        for (name in fileDbs) {
            try {
                context.deleteDatabase(name)
            } catch (_: Exception) {
            }
        }
        fileDbs.clear()
    }

    fun openInMemoryDb(): GymDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java).build()

    fun openGatedInMemoryDb(gate: GatedTransactionExecutor): GymDatabase {
        gatedExecutors += gate
        return Room.inMemoryDatabaseBuilder(context.applicationContext, GymDatabase::class.java)
            .setTransactionExecutor(gate)
            .build()
    }

    /** Create-once file DB: deletes any prior copy, then builds and tracks it. */
    fun createFileDb(name: String): GymDatabase {
        fileDbs += name
        context.deleteDatabase(name)
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    /** Reopen WITHOUT delete: the same physical DB must survive close/reopen. */
    fun reopenFileDb(name: String): GymDatabase {
        if (!fileDbs.contains(name)) fileDbs += name
        return Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, name).build()
    }

    fun newVault(): SessionVault {
        deleteNamespacePath(namespace)
        return AndroidSessionVault.createForTesting(context, namespace)
    }

    fun reopenVault(): SessionVault = AndroidSessionVault.createForTesting(context, namespace)

    /**
     * Fresh singleton rows are absent (empty list), not an error: unconfigured
     * and legacy baselines have no journal/device row yet.
     */
    fun journalRow(database: GymDatabase): List<String?> {
        database.openHelper.readableDatabase.query(
            "SELECT revision, session_generation, phase, service_origin, account_id, operation_id" +
                " FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            return (0 until 6).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
    }

    fun journalRevision(database: GymDatabase): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT revision FROM sync_session_authority WHERE id = 1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    fun deviceRow(database: GymDatabase): List<String?> {
        database.openHelper.readableDatabase.query(
            "SELECT active_account_id, cached_account_id, session_generation, cache_state" +
                " FROM sync_device_state WHERE id = 1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            return (0 until 4).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
    }

    fun outboxCount(database: GymDatabase, account: String): Long {
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_outbox WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "count must return a row" }
            return cursor.getLong(0)
        }
    }

    /** Full outbox dump for exact preservation oracles (ordered, exact cells). */
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

    /**
     * Frozen retry identity: mutation/request bytes only. Transient backoff
     * legitimately changes attempt/retry bookkeeping columns, so those are
     * excluded; identity must stay byte-identical.
     */
    fun frozenIdentity(rows: List<List<String?>>): List<List<String?>> =
        rows.map { it.subList(1, 9) }

    fun syncStateCursor(database: GymDatabase, account: String): String? {
        database.openHelper.readableDatabase.query(
            "SELECT cursor FROM sync_state WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return if (cursor.isNull(0)) null else cursor.getString(0)
        }
    }

    fun cacheEpoch(database: GymDatabase, account: String): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT cache_epoch FROM sync_account_cache WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    fun materializedEpoch(database: GymDatabase, account: String): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT materialized_epoch FROM sync_account_cache WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    /** Canonical row oracle: server revision and state for one staged root. */
    fun canonicalRevision(
        database: GymDatabase,
        account: String,
        aggregateType: String,
        aggregateId: String,
    ): Pair<Long, String?>? {
        database.openHelper.readableDatabase.query(
            "SELECT server_revision, canonical_state FROM sync_canonical_roots" +
                " WHERE account_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            arrayOf(account, aggregateType, aggregateId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0) to if (cursor.isNull(1)) null else cursor.getString(1)
        }
    }

    fun projectionDirty(database: GymDatabase, account: String): Long? {
        database.openHelper.readableDatabase.query(
            "SELECT projection_dirty FROM sync_account_cache WHERE account_id = ?",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
    }

    /** Supplementary SQL oracle for user-created exercise names. */
    fun customNames(database: GymDatabase, account: String): List<String> {
        database.openHelper.readableDatabase.query(
            "SELECT name FROM exercises WHERE is_user_created = 1 AND account_id = ? ORDER BY name",
            arrayOf(account),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun deleteRecursivelyOwned(root: File) {
        if (!root.exists()) return
        if (root.isDirectory) {
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
            java.security.KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                .deleteEntry("com.dragosfotache.gymtracka.session.$ns")
        } catch (_: Exception) {
        }
    }
}
