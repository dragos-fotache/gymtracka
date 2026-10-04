# R04b — Serialized refresh and bounded access recovery

Status: **coordinator-accepted (2026-10-04)**; [actual review/gates](R04b-review.md):726 forced offline JVM/99 focused,
Kotlin/Android-test compilation, assembly/lint/APK/preservation passed, zero test failures/errors/skips.
Owner: “start work at R04b”.
Parent [R04 split](R04-task.md); accepted prerequisites R03a–c/R04a. Stop after b; c/R09/activation need separate authorization.

## Goal and boundary

Deliver UNUSED application-owned refresh machinery over the accepted encrypted vault and ServiceAuthApi, and an UNUSED
GymSyncApi decorator proving one refresh-and-unchanged-retry. Persist before send, recover exact uncertain attempts,
persist complete returned grants before use, serialize selection/rotation, and fence stale responses with durable CAS
and explicitly revocable in-memory leases. Keep production constructors available; nothing constructs these in the app.

No startup reconciliation, Room identity/journal/schema/migration, sign-in/out orchestration, Google/UI/engine/scheduler,
account activation, configuration/deployment/backend operation/source/suite, dependency/build/manifest/backup XML/asset
change, commit/push or next slice. A lease is an explicit internal authorization seam for later R04c/R09, NOT proof that
vault phase alone authorizes network/UI. Tests may issue leases; production activation must reconcile both stores first.

## Normative reads and existing API facts

Both contributors read root AGENTS, this spec, R01-design §6, shared backend-contract/authentication.md,
client-compatibility.md and issues/auth-expiry-precision.md. Do not read other role's source or review reports.
Coordinator read current architecture/product/testing/roadmap/design and the full shared contract map before this spec.

Existing facts (QA uses these, never production declarations):
- `data.sync.api.OpaqueToken(value: String)`: NONEMPTY exact opaque text, value equality, redacted display.
  `ServiceAccountRef(accountId: String, displayName: String?)`: canonical lowercase UUID.
  `ServiceSession(account, accessToken, accessExpiresAtMillis, refreshToken, refreshExpiresAtMillis)` immutable data class;
  inclusive wire-millis bounds [-62167219200000,253402300799999], no freshness/order validation. Grant equality is value based.
- `ServiceRefreshAttempt.capture(idempotencyKey: String, refreshToken: OpaqueToken)` encodes once;
  `.fromJson(idempotencyKey: String, requestJson: String)` restores EXACT valid original body. Properties key/body/token.
  Attempt equality itself is identity based; compare key/body/token explicitly across vault decode/restart.
- `ServiceAuthApi` suspend methods: `challenge(purpose: GoogleChallengePurpose = SIGN_IN): ServiceChallenge`,
  `exchange(attempt: GoogleExchangeAttempt): ServiceSession`, `refresh(attempt: ServiceRefreshAttempt): ServiceSession`,
  `logout(refreshToken: OpaqueToken)`. This slice calls ONLY typed refresh. All safe API failures are SyncApiException.
- `SyncApiException` sealed Exception(code,message,null): zero-arg AuthenticationRequired, ReauthenticationRequired,
  MalformedResponse, InvalidCursor, CursorExpired, InvalidSnapshotToken, SnapshotExpired, RequestTooLarge;
  `Transient(retryAfterMillis: Long? = null)` caps hint to0..300000; `RequestRejected(errorCode: String)` retains exact code.
  Causes closed; no raw cause/message constructors. Cancellation must retain supplied identity inside caller.
- `data.sync.http.ServiceOrigin.parse(String)` canonical root HTTPS origin; equality canonical identity.
  `ServiceSessionBinding(origin, accountId: String, sessionGeneration: Long)` data class; canonical UUID/positive generation,
  binding equality all three. `BoundServiceAccessToken(binding, token: OpaqueToken)` immutable/redacted.
  `BoundServiceAccessTokenProvider` fun interface suspend `accessToken(binding): BoundServiceAccessToken`.
- R04a `data.sync.session`: enum `VaultSessionPhase { PREPARED, ACTIVE }`;
  `VaultPendingRefresh(operationId: String, attempt: ServiceRefreshAttempt)`;
  `VaultSessionRecord(binding, operationId: String, phase, session: ServiceSession, pendingRefresh: VaultPendingRefresh? = null)`;
  `VaultSnapshot(revision: Long, record: VaultSessionRecord?)` immutable validating data classes/redacted.
  Record account matches binding, pending token matches stored refresh; both IDs canonical UUID. Revision0 only null.
  `SessionVault` suspend `load(): VaultSnapshot`, `compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot`.
  CAS checked revision increment, whole encrypted commit; clear positive tombstone. CAS failure/cancellation may have committed;
  NEVER infer rollback. Exception `SessionVaultException(failure: VaultFailure)` fixed message/closed cause;
  enum INVALID_RECORD,UNREADABLE,UNSUPPORTED_VERSION,UNAVAILABLE,CONFLICT,REVISION_EXHAUSTED.
- Real-vault deterministic seams: `VaultFileStorage.read(): ByteArray?`, `.replace(bytes: ByteArray)`;
  `VaultCipher.seal(plaintext: ByteArray): ByteArray`, `.open(sealed: ByteArray): ByteArray`;
  `EncryptedSessionVault(storage,cipher,ioDispatcher: CoroutineDispatcher=Dispatchers.IO,mutex: Mutex=Mutex())`.
  Independent in-memory ciphertext storage + defensive identity cipher can prove real codec/CAS restarts without Android.
  `VaultKeyProvider.key(createIfMissing: Boolean): SecretKey`; `AesGcmVaultCipher(keyProvider)` permits real JVM JCA integration.
- `GymSyncApi`: suspend push(List<DurableRequestModel>): PushResultModel, pull(String?,Int=100): PullResultModel,
  snapshot(String?,Int=200): SnapshotResultModel. Immutable durable model `decodeDurableRequest(String)` restores v1 wrapper;
  `encodePushMutationsBody(List<DurableRequestModel>)` validates and embeds exact requestJson. No client-owned account body.
  Pull bounds1..200, snapshot1..500 (adapter clamps). Coordinator supplies additional model/fixture facts on request.
- `HttpGymSyncApi(binding,provider,timeouts=ServiceHttpTimeouts())` production constructor; internal `(binding,provider,transport)`.
  Every explicit method selects provider once and sends once. Binding mismatch/no representable bearer -> AuthenticationRequired
  before HTTP. Complete responses strictly parsed; 401 access or explicit reauth categories; no retry inside adapter.
  `HttpServiceAuthApi(origin,timeouts=ServiceHttpTimeouts())`; internal `(origin,transport)`; refresh sends exact body/header key once.
- Test transport: `ServiceHttpTransport` interface suspend `<T> withResponse(request: ServiceHttpRequest,
  consume: suspend (ServiceHttpResponse)->T): T`. Request fields method/path/query/headers/body immutable snapshots.
  `ServiceHttpResponse(call: okhttp3.Call,response: okhttp3.Response)` internal constructor; test must close with
  `closeAfterConsumer()` finally. Ordinary OkHttp Response.Builder/ResponseBody and local MockWebServer3/TLS are available.
  `ServiceHttpTimeouts(connectMillis=10000,readMillis=30000,writeMillis=30000,callMillis=60000)`.

## New concrete API — all internal, package data.sync.session

Use exact signatures/names; QA must not inspect declarations. Keep new files focused.

```kotlin
enum class SessionRefreshFailure { INVALID_INPUT, STALE_SESSION, ACCESS_UNAVAILABLE, UNAVAILABLE }
class SessionRefreshException(val failure: SessionRefreshFailure) : RuntimeException // fixed message, cause closed null
class SessionRefreshLease(
    val binding: ServiceSessionBinding,
    val operationId: String,
    val phase: VaultSessionPhase,
) {
    fun invalidate() // synchronous, idempotent, thread safe; never waits for network/vault/mutex
}
class SessionRefreshCoordinator(
    private val origin: ServiceOrigin,
    private val vault: SessionVault,
    private val authApi: ServiceAuthApi,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val mutex: Mutex = Mutex(),
) {
    suspend fun accessToken(lease: SessionRefreshLease): BoundServiceAccessToken
    suspend fun <T> withAccessToken(
        lease: SessionRefreshLease,
        request: suspend (BoundServiceAccessToken) -> T,
    ): T
}
class SessionRefreshingGymSyncApi internal constructor(
    coordinator: SessionRefreshCoordinator,
    lease: SessionRefreshLease,
    transport: ServiceHttpTransport,
) : GymSyncApi {
    constructor(
        coordinator: SessionRefreshCoordinator,
        lease: SessionRefreshLease,
        timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    )
}
```

Lease operation validates canonical UUID -> INVALID_INPUT; binding already validates. Every new object's display is
fixed/redacted, exceptions cannot retain arbitrary causes/suppressed raw failures. No mutable public lease status setter,
reactivate/reset method or auto lease from vault. Private atomic lease states usable / reauth-required / invalidated;
invalidation dominates and is permanent. Reauth latch is **process-local**, not a durable startup/Room decision.
One app-owned coordinator/mutex and ONE shared lease per authorized activation are used by future foreground/background
users; no multiprocess, independently constructed concurrent coordinator, or duplicate-lease activation claim. Inject mutex only for deterministic tests. No constructor I/O/key/HTTP.
Auth adapter must be constructed for coordinator's SAME origin by owning composition; interface cannot introspect origin.
Decorator creates secure transport for lease origin, while coordinator rejects lease origin unequal configured origin.

## Selection and rotation algorithm

1. Check caller active and lease usable before any suspension and again after mutex acquisition. Mismatched origin,
   absent/tombstoned record, or record binding/operation/phase mismatch -> STALE_SESSION, no UUID/write/HTTP.
   Never implicitly adopt/promote another phase, generation, account or operation. Both phases usable ONLY by explicit matching lease.
2. Under run mutex, LOAD current durable vault (no credential success cache). If pending exists, settle it FIRST even if
   access appears live OR old refresh expiry passed. The consumed old token's expiry cannot decide validity of replay's
   returned current refresh. Reuse its EXACT operation/key/body/token; no UUID calls, recapture or reserialization.
3. With no pending and accessExpiresAtMillis > clockMillis(), return exact tagged access credential, no write/HTTP.
   Access expiry at equality schedules refresh; no safety skew/arithmetic/lifetime extension. If refresh is needed and
   no pending and refreshExpiresAtMillis <= now, latch foreground reauth and throw ReauthenticationRequired without
   creating/transmitting a new attempt. Local clock is a conservative scheduling hint, never deletion/expiry rewriting authority.
4. Otherwise obtain TWO canonical UUIDs (pending operation then key), capture body ONCE from current refresh, and CAS
   whole current grant + pending at loaded revision. Invalid generator/fault -> closed safe failure, no HTTP.
   Returned CAS must succeed durably before HTTP. Reload and verify exact sent revision/record/attempt and live lease
   before transmission; intervening changes/conflict -> no send. Do not loop/rebase/retry CAS or invent another key.
5. Call ONLY `authApi.refresh(pending.attempt)` once outside any short lease-state lock (run mutex may span HTTP).
   On cancellation/transient/malformed/permanent/storage failure retain uncertain pair and original grant unchanged
   (except a complete uncertain commit), no automatic network retry/backoff. Safe categories/hints propagate; unexpected
   auth seam errors -> closed Transient. Auth AuthenticationRequired is wrong-context and -> ReauthenticationRequired.
   Refresh ReauthenticationRequired latches lease, without clearing vault/account/cache; later calls on SAME lease do no HTTP.
6. Successful grant must match account exactly; otherwise MalformedResponse and retain pending. Do not validate invented
   lifetime/order/token-format rules or round/extend expiry. Recheck caller, lease and original binding/op/phase, then CAS
   at SENT pending revision to whole returned grant + null pending. Never retry stale result CAS. Preserve original operation/phase.
   No returned credential reaches caller/HTTP until that full commit succeeds AND subsequent reload/fence validates it.
7. Original replay can have expired access. FIRST persist its complete grant and clear old pending, then, only if needed,
   create a NEW attempt using its returned CURRENT refresh (step3/4). Max TWO refresh calls per selection: pending replay
   plus one fresh rotation, or one fresh rotation for no pending. If resulting access remains expired after fresh rotation,
   preserve committed grant and throw ACCESS_UNAVAILABLE; no automatic third refresh/loop. Returned expired refresh
   while access needs renewal triggers foreground reauth, not a new key for its consumed predecessor.

Any vault exception propagates unchanged safe category (including CONFLICT/overflow/corrupt/key absence); no silent empty,
repair, tombstone/reset or key deletion. UUID/clock unexpected errors -> UNAVAILABLE; invalid generated UUID -> INVALID_INPUT.
All branch/error exits first recheck actual caller activity so original caller cancellation dominates late errors and
context-recovery copies. Injected active-context CancellationException stays same instance inside caller. No unstructured
jobs, NonCancellable network, internal coroutine APIs, cause unwrap or disabled stack recovery.

## One access rejection recovery, unchanged request

`withAccessToken` selects a private credential+vault-revision observation under the serialized path above, then releases
run mutex before invoking callback with that exact tagged token. Callback is a transport operation, not Room/app mutation;
owner must freeze business inputs before entry. Check lease/caller and reload durable identity before callback admission
and after completion before returning a result. Never expose a stale successful result to the caller.

Only first callback `AuthenticationRequired` permits recovery. Under mutex reload/fence: if another caller already installed
a newer no-pending usable grant after this observation, reuse it (do not rotate twice); otherwise settle pending or force
one serialized fresh refresh even when rejected access's time appears live. Then retry SAME callback once, never more than
TWO callback invocations. Any second AuthenticationRequired -> latch foreground reauth and throw ReauthenticationRequired.
Direct ReauthenticationRequired on either callback latches immediately, no recovery. Other safe categories/per-mutation
rejections/cancellation propagate without resend; unexpected callback exceptions become closed Transient. On late errors,
check caller cancellation FIRST, then invalidated lease/durable identity before surfacing the old operation's category.
Preserve active-context safe exception identity when no overriding fence/cancellation applies. A lost response is not proof of zero writes; this wrapper does not
acknowledge/drop outbox intent. Pending/refresh failures prevent the callback retry.

`SessionRefreshingGymSyncApi` snapshots/validates push inputs BEFORE suspension; duplicate/count/model/unsupported-version
preflight errors retain their approved types and cause no credential I/O. Existing durable data classes have read-only
but caller-mutable nested lists: take a private validated deep wrapper snapshot using encodeDurableRequest followed by
decodeDurableRequest (local wrapper ONLY, original requestJson remains EXACT; never reserialize the mutation).
Then construct each accepted HttpGymSyncApi invocation with a fixed provider returning ONLY the supplied credential,
and delegate inside withAccessToken. Request values/requestJson/IDs/order, cursor/pageToken/limit survive both attempts
exactly despite caller mutations; no recapture/regeneration/null-token restart.
Default HTTPS origin/timeouts/normal TLS stay in the accepted transport. Decorator has no account-global bearer cache.
No change to approved HttpGymSyncApi, API failures or durable codec required.

## Invalidation and two-store limits

`lease.invalidate()` is immediate even while run mutex/HTTP/vault is blocked, stops future admission and makes waiting/
late results STALE_SESSION (caller cancellation still dominates). It performs NO durable cleanup, Room change, logout,
Google clear or cache mutation. Already selected/transmitted bytes cannot be recalled; owner later cancels owned network.
A synchronously in-progress vault CAS may finish after invalidation, but credentials/results are never returned/admitted
under that lease. Later R09 must durably tombstone/fence as part of sign-out; invalidation alone is not successful sign-out.
External clear/replacement bumps revision; delayed result CAS cannot reinstall over tombstone/B/new-generation/same-binding
new-operation. Tests gate both ordering sides. Do not claim atomicity between lease invalidation, vault and future Room.
If durable binding changes after final recheck but before actual network entry, later server/application fences still matter;
no local lock can retract a request already admitted. Recheck boundaries, don't promise impossible zero-window atomicity.

**Legacy limitation:** strict incompatible pre-fix replay remains MalformedResponse with original uncertain pair preserved.
NEVER rounding/replay rewrite/new key for old consumed token/blanket revocation/inferred cache deletion. A known still-current
legacy refresh without pending can rotate freshly. No test or review labels legacy recovery repaired.

## Disjoint ownership and workflow

Worker NEW production files ONLY under `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/`:
`SessionRefreshFailures.kt`, `SessionRefreshLease.kt`, `SessionRefreshCoordinator.kt`, `SessionRefreshingGymSyncApi.kt`.
READ ONLY accepted session vault, api, http, codecs/model needed for delegation; normative docs. No tests/fixtures/reports,
QA transcripts or test bytecode, no approved existing production edits. Ask coordinator for a narrowly reviewed API blocker.

QA NEW tests ONLY under `app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/`:
`SessionRefreshCoordinatorTest.kt`, `SessionRefreshingGymSyncApiTest.kt`, `RefreshTestFixtures.kt`.
No production source/declarations/diffs/bytecode, Worker work, old tests or broad reports. Own filtered reports/normative
spec allowed; request missing fixture/API facts from coordinator. Do not derive expected bytes/results from production encoders.
No Android test/source changes initially; coordinator may add a bounded construction/platform smoke only if review needs it.

Fresh native Pi /new sessions per role, Muse Spark1.3 Contributor/high, corrections same sessions. Restricted mcpls preferred;
bounded indexing/unavailable fallback exact own-file reads and compiler. Shared Gradle slot explicitly serialized by
coordinator; NO Gradle until granted. Fire-and-forget `herdr agent prompt gym-coordinator "..."`, NO --wait/agent wait.
Neither role touches backend/services/emulators/global config/docs/commit/push/unrelated files.

## Acceptance matrix and gate

Independent deterministic JVM (TestScope/virtual time/gates, no sleeps):
- inert constructors; exact binding/op/phase/no implicit authorization; fresh live access; equality expiry, overflow-safe Long
  clock comparisons, invalid generators; pending first despite live access/expired predecessor; original expiry/token fields.
- persist-before-send observation; write-before/after-commit failure and reload/restart using REAL EncryptedSessionVault;
  immutable original request whitespace/escapes/Unicode/key; replay grant stored before next CURRENT-token fresh attempt;
  expired replay and fresh-expired bounded stop; pending failure retention; malformed account result; strict legacy failure.
- callback 401 then success, concurrent expired callers and concurrent rejected old credential (one rotation), repeated401/
  explicit reauth latch, safe nonauth categories/hints/permanent codes/no resend, byte-exact push/order and identical read queries.
- vault load/CAS/conflict/overflow/unreadable/missing-key failures; external A/B/origin/generation/operation/phase changes,
  tombstone ABA/stale result refusal; invalidate while blocked HTTP/mutex/CAS and late success/error/cancel dominance.
- supplied cancellation identity asserted INSIDE caller at load/save/send/receive/callback/waiting; current caller cancel
  dominates racing categories; no cancelled waiting write; uncertain committed state reload, never rollback assumption.
- secret/redaction/closed causes; restart reconstruction loses only lease latch, never pending identity; no persistent Google
  or unauthenticated fallback. Actual HttpServiceAuthApi + decorator through scripted transport verifies real body/header
  and 401 routing (client fixture, NOT server simulator). JCA vault integration where feasible without Android.

Coordinator reviews all production/tests/new files and whole diff; count alone not acceptance. Focused JVM first, then forced
full offline JVM, production + Android-test compilation, assembly/lint serially; inspect all executed XML testcases and
failures/errors/skips. APK helper exclusion, unchanged asset/schema/build/app/backup/manifest and entire shared checkout
hash preservation, both diff checks. No new backend/device/Google/full-v1/API24/Room convergence pass is required or claimed
by this unused client-state slice; prior R04a27-device/R03c auth10/broad56 remain prior evidence.
Update architecture/testing/README/roadmap/handoff/parent and new R04b-review with actual evidence and limits; stop after b.
Pickup Android04f7ed2/shared31c969f; seven existing Android delivery-receipt doc modifications preserved; shared clean.
