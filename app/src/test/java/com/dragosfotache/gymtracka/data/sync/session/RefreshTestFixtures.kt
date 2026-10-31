package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpResponse
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

/**
 * ONE shared lease per activation: every helper builds a single lease per
 * vault activation; concurrent callers in a test share that lease, matching
 * the caller.
 */
internal object RefreshTestFixtures {
    const val ACCOUNT_ID = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
    const val OTHER_ACCOUNT_ID = "c6938596-d7e7-50bb-0689-8b3f748dccd1"
    const val ORIGIN_ROOT = "https://sync.example.test"
    const val OTHER_ORIGIN_ROOT = "https://other.example.test"

    const val OP_A = "11111111-1111-1111-1111-111111111111"
    const val OP_B = "22222222-2222-2222-2222-222222222222"
    const val KEY_A = "33333333-3333-3333-3333-333333333333"
    const val KEY_B = "44444444-4444-4444-4444-444444444444"

    const val NOW = 1_700_000_000_000L
    const val FIFTEEN_MIN = 15L * 60 * 1000
    const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000

    fun origin(root: String = ORIGIN_ROOT): ServiceOrigin = ServiceOrigin.parse(root)

    fun binding(
        root: String = ORIGIN_ROOT,
        accountId: String = ACCOUNT_ID,
        generation: Long = 1L,
    ): ServiceSessionBinding = ServiceSessionBinding(
        origin = origin(root),
        accountId = accountId,
        sessionGeneration = generation,
    )

    fun accountRef(accountId: String = ACCOUNT_ID): ServiceAccountRef =
        ServiceAccountRef(accountId = accountId, displayName = null)

    fun opaque(value: String): OpaqueToken = OpaqueToken(value)

    fun session(
        accountId: String = ACCOUNT_ID,
        accessValue: String = "access-1",
        accessExpiresAtMillis: Long = NOW + FIFTEEN_MIN,
        refreshValue: String = "refresh-1",
        refreshExpiresAtMillis: Long = NOW + THIRTY_DAYS,
    ): ServiceSession = ServiceSession(
        account = accountRef(accountId),
        accessToken = opaque(accessValue),
        accessExpiresAtMillis = accessExpiresAtMillis,
        refreshToken = opaque(refreshValue),
        refreshExpiresAtMillis = refreshExpiresAtMillis,
    )

    fun record(
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
        session: ServiceSession = session(accountId = binding.accountId),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = VaultSessionRecord(
        binding = binding,
        operationId = operationId,
        phase = phase,
        session = session,
        pendingRefresh = pendingRefresh,
    )

    fun pending(
        operationId: String = OP_A,
        key: String = KEY_A,
        refreshValue: String = "refresh-1",
    ): VaultPendingRefresh {
        val attempt = ServiceRefreshAttempt.capture(
            idempotencyKey = key,
            refreshToken = opaque(refreshValue),
        )
        return VaultPendingRefresh(operationId = operationId, attempt = attempt)
    }

    /** Single shared lease for one activation; concurrent callers share it. */
    fun sharedLease(
        binding: ServiceSessionBinding = binding(),
        operationId: String = OP_A,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
    ): SessionRefreshLease = SessionRefreshLease(
        binding = binding,
        operationId = operationId,
        phase = phase,
    )

    fun coordinator(
        vault: SessionVault,
        authApi: ServiceAuthApi,
        clockMillis: () -> Long = { NOW },
        uuids: Iterator<String> = sequenceOf(OP_A, KEY_A).iterator(),
        mutex: Mutex = Mutex(),
    ): SessionRefreshCoordinator = SessionRefreshCoordinator(
        origin = origin(),
        vault = vault,
        authApi = authApi,
        clockMillis = clockMillis,
        uuidProvider = { uuids.next() },
        mutex = mutex,
    )

    fun uuidProvider(vararg values: String): () -> String {
        val it = values.iterator()
        return { it.next() }
    }

    fun throwingUuidProvider(error: RuntimeException = RuntimeException("uuid-fault")): () -> String {
        return { throw error }
    }



    const val EMPTY_PUSH_BODY = """{"results":[]}"""
    const val EMPTY_PULL_BODY = """{"batches":[],"nextCursor":"same-input","hasMore":false}"""
    const val EMPTY_SNAPSHOT_BODY =
        """{"records":[],"baselineCursor":"baseline","nextPageToken":null,"hasMore":false}"""

    fun rejectedPushBodyFor(mutationIds: List<String>): String =
        """{"results":[${mutationIds.joinToString(",") { id -> """{"mutationId":"$id","status":"rejected","errorCode":"fixture","batch":null}""" }}]}"""

    fun rejectedPushBodyAB(): String = rejectedPushBodyFor(listOf(MUT_A, MUT_B))

    /** Fresh grant JSON using contract field names; millis-precision instants. */
    fun grantBody(
        accessValue: String = "access-2",
        accessAt: String = "2026-09-27T12:15:00Z",
        refreshValue: String = "refresh-2",
        refreshAt: String = "2026-10-27T12:00:00Z",
        accountId: String = ACCOUNT_ID,
    ): String = """{"account":{"id":"$accountId","displayName":null},""" +
        """"accessToken":"$accessValue","accessExpiresAt":"$accessAt",""" +
        """"refreshToken":"$refreshValue","refreshExpiresAt":"$refreshAt"}"""

    /** Strict legacy replay: nonzero sub-millisecond tail stays MalformedResponse. */
    fun legacyGrantBody(): String =
        """{"account":{"id":"$ACCOUNT_ID","displayName":null},""" +
            """"accessToken":"legacy-access","accessExpiresAt":"2026-09-27T12:15:00.123456789Z",""" +
            """"refreshToken":"legacy-refresh","refreshExpiresAt":"2026-10-27T12:00:00.000001Z"}"""

    fun unauthorizedBody(errorCode: String = "access_expired"): String =
        """{"errorCode":"$errorCode"}"""

    fun reauthBody(): String = """{"errorCode":"reauthentication_required"}"""



    const val MUT_A = "55555555-5555-5555-5555-555555555555"
    const val MUT_B = "66666666-6666-6666-6666-666666666666"
    const val ROOT_A = "77777777-7777-7777-7777-777777777777"
    const val ROOT_B = "88888888-8888-8888-8888-888888888888"

    const val REQ_JSON_A =
        """{"mutationId":"55555555-5555-5555-5555-555555555555","type":"aggregate.delete","aggregateType":"workout","aggregateId":"77777777-7777-7777-7777-777777777777","localGeneration":1,"payload":{}}"""
    const val REQ_JSON_B =
        """{"mutationId":"66666666-6666-6666-6666-666666666666","type":"aggregate.delete","aggregateType":"workout","aggregateId":"88888888-8888-8888-8888-888888888888","localGeneration":1,"payload":{}}"""

    val WRAPPER_A: String = wrapperFor(1, REQ_JSON_A, ROOT_A)
    val WRAPPER_B: String = wrapperFor(1, REQ_JSON_B, ROOT_B)

    fun wrapperWithVersion(version: Int): String = wrapperFor(version, REQ_JSON_A, ROOT_A)

    private fun jsonEscape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun wrapperFor(version: Int, requestJson: String, rootId: String): String =
        "{\"payloadVersion\":" + version + ",\"requestJson\":\"" + jsonEscape(requestJson) +
            "\",\"affectedRoots\":[{\"aggregateType\":\"workout\",\"aggregateId\":\"" + rootId +
            "\",\"localGeneration\":1}]}"
}

internal suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError(
            "Expected " + (T::class.simpleName ?: "?") + " but was " +
                (e::class.simpleName ?: "?") + ": " + e.javaClass.name,
        )
    }
    throw AssertionError("Expected " + (T::class.simpleName ?: "?") + " but no exception was thrown")
}

internal fun failNow(msg: String): Nothing = throw AssertionError(msg)

/**
 * In-memory SessionVault obeying the spec CAS contract: checked revision
 * increment, whole-record commit, tombstone clear, CONFLICT on stale
 * revision, REVISION_EXHAUSTED at Long.MAX_VALUE. CAS failure/cancellation
 * may have committed; callers must never infer rollback.
 */
internal class FakeSessionVault(
    initial: VaultSnapshot = VaultSnapshot(revision = 0L, record = null),
) : SessionVault {
    private val mutex = Mutex()
    private var snapshot: VaultSnapshot = initial
    var loads = 0
        private set
    var commits = 0
        private set
    val committedRevisions = mutableListOf<Long>()
    val committedRecords = mutableListOf<VaultSessionRecord?>()

    /** One-shot failures keyed by CAS call index; null entries succeed. */
    val casFailures = mutableListOf<SessionVaultException?>()

    /** Load failures queued in order; consumed per load call. */
    val loadFailures = mutableListOf<SessionVaultException>()

    /** Gate invoked inside load while holding no vault lock. */
    var loadGate: (suspend () -> Unit)? = null

    /** Block the next [blockCasCalls] CAS calls until [releaseCas] completes. */
    var blockCasCalls: Int = 0
    val releaseCas = CompletableDeferred<Unit>()

    suspend fun current(): VaultSnapshot {
        mutex.lock()
        try {
            return snapshot
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun load(): VaultSnapshot {
        loadGate?.invoke()
        mutex.lock()
        try {
            val injected = if (loadFailures.isNotEmpty()) loadFailures.removeAt(0) else null
            if (injected != null) throw injected
            loads += 1
            return snapshot
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long,
        record: VaultSessionRecord?,
    ): VaultSnapshot {
        if (blockCasCalls > 0) {
            blockCasCalls -= 1
            releaseCas.await()
        }
        mutex.lock()
        try {
            val callIndex = commits
            commits += 1
            val injected = casFailures.getOrNull(callIndex)
            if (injected != null) throw injected
            if (snapshot.revision != expectedRevision) {
                throw SessionVaultException(VaultFailure.CONFLICT)
            }
            if (expectedRevision == Long.MAX_VALUE) {
                throw SessionVaultException(VaultFailure.REVISION_EXHAUSTED)
            }
            val next = VaultSnapshot(revision = expectedRevision + 1, record = record)
            snapshot = next
            committedRevisions += next.revision
            committedRecords += record
            return next
        } finally {
            mutex.unlock()
        }
    }
}

/** Scripted ServiceAuthApi refresh seam. Only typed refresh is exercised. */
internal class FakeServiceAuthApi : ServiceAuthApi {
    var refreshCalls = 0
        private set
    val seenAttempts = mutableListOf<ServiceRefreshAttempt>()

    /** Script per refresh call index; absent entry throws Transient(null). */
    val script = mutableListOf<suspend (ServiceRefreshAttempt) -> ServiceSession>()

    /** Gate invoked per refresh call before the scripted outcome. */
    var refreshGate: (suspend (ServiceRefreshAttempt) -> Unit)? = null

    fun answer(session: ServiceSession) {
        script += { _ -> session }
    }

    fun failWith(error: SyncApiException) {
        script += { _ -> throw error }
    }

    fun cancelWith(id: kotlinx.coroutines.CancellationException) {
        script += { _ -> throw id }
    }

    fun failWithUnexpected(error: RuntimeException) {
        script += { _ -> throw error }
    }

    override suspend fun challenge(
        purpose: com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose,
    ): com.dragosfotache.gymtracka.data.sync.api.ServiceChallenge {
        throw UnsupportedOperationException("refresh uses refresh only")
    }

    override suspend fun exchange(
        attempt: com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt,
    ): ServiceSession {
        throw UnsupportedOperationException("refresh uses refresh only")
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
        throw UnsupportedOperationException("refresh uses refresh only")
    }
}

/** In-memory ciphertext storage for real EncryptedSessionVault restart tests. */
internal class InMemoryVaultFileStorage : VaultFileStorage {
    private var bytes: ByteArray? = null
    private val lock = Any()

    fun seed(bytes: ByteArray?) {
        synchronized(lock) { this.bytes = bytes?.copyOf() }
    }

    override fun read(): ByteArray? = synchronized(lock) { bytes?.copyOf() }

    override fun replace(bytes: ByteArray) {
        synchronized(lock) { this.bytes = bytes.copyOf() }
    }
}

/** Defensive identity cipher: copies bytes, never retains caller arrays. */
internal class IdentityVaultCipher : VaultCipher {
    override fun seal(plaintext: ByteArray): ByteArray = plaintext.copyOf()

    override fun open(sealed: ByteArray): ByteArray = sealed.copyOf()
}

internal fun realVault(
    storage: VaultFileStorage,
    cipher: VaultCipher = IdentityVaultCipher(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): EncryptedSessionVault = EncryptedSessionVault(
    storage = storage,
    cipher = cipher,
    ioDispatcher = dispatcher,
    mutex = Mutex(),
)

private val fakeCallClient: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }

private fun fakeCall(request: Request): okhttp3.Call = fakeCallClient.newCall(request)

/**
 * Scripted ServiceHttpTransport. Each queued frame is served once in order;
 * every served frame captures its immutable request snapshot. Responses are
 * real OkHttp responses routed through the internal ServiceHttpResponse
 * constructor and always closed via closeAfterConsumer() in finally, per spec.
 */
internal class FakeServiceHttpTransport : ServiceHttpTransport {
    data class Frame(val code: Int, val body: String)

    val queue = ArrayDeque<Frame>()
    val capturedRequests = mutableListOf<com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest>()

    /** Gate invoked inside withResponse before serving the next frame. */
    var responseGate: (suspend () -> Unit)? = null

    /** Optional per-call serving override; when set, replaces the FIFO queue. */
    var serve: ((callIndex: Int, request: com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest) -> Frame)? = null
    private var calls = 0

    fun queueJson(code: Int, body: String) {
        queue.addLast(Frame(code, body))
    }

    fun queueOk(body: String) = queueJson(200, body)

    override suspend fun <T> withResponse(
        request: com.dragosfotache.gymtracka.data.sync.http.ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T {
        responseGate?.invoke()
        capturedRequests += request
        calls += 1
        val frame = serve?.invoke(calls, request)
            ?: queue.removeFirstOrNull()
            ?: throw AssertionError("FakeServiceHttpTransport exhausted: no queued response")
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
        val wire = ServiceHttpResponse(fakeCall(httpRequest), httpResponse)
        try {
            return consume(wire)
        } finally {
            wire.closeAfterConsumer()
        }
    }
}
