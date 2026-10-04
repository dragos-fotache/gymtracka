# R04c — Startup/session recovery seams

Status: accepted on 2026-10-04; historical task specification. Owner authorized R04c planning and implementation. This
was the normative contract for Worker and independent QA; current guidance lives in active documentation.
No next-slice, activation, commit/push, deployment or private-configuration authority follows.

## Goal and scope

Deliver unused, SDK-independent startup privacy/recovery coordination over the accepted vault and refresh machinery.
A future R09 Room adapter supplies explicit durable authorization observations. No default adapter or app construction
is supplied. Startup itself performs reads only: no refresh, vault repair/promotion/clear, Room mutation or logout.

Dependencies: R03a/c and accepted R04a/b. Starting Android HEAD `7e00bbbc0130806f71112a099b05dc0e91b01f39`;
shared HEAD `31c969f261bd3f0fe662e633c0dfffecb3c08393`; both trees clean at pickup. Room remains v9, the bundled v1
asset unchanged. The production repository still binds signed-out null/0. Existing session HTTP machinery is unused.

Read `docs/sync/implementation.md`, integration design section 6, testing strategy, and shared authentication/protocol/
compatibility rules. Shared requirements are finalized; do not reopen them. Preserve the documented legacy incompatible
accepted-replay limitation: strict malformed replay retains the exact pending operation/key/body/token; never round,
rewrite, invent a new uncertain key, blanket-revoke, or infer cache deletion.

## Non-goals and R09 boundary

No Room entities/DAOs/migration, transition journal implementation, activation/promote/clear/import/replace/sign-out
orchestration, account projection/publication, engine, Google UI, scheduler, production construction/configuration, backend
source/service operations or device/account action. Do not implement a fake Room repository or second auth/server engine.
A recovery observation/handle is process-local authorization evidence, NOT a Room transaction/lease: later R09/R12 still
must implement durable generation changes, finite-edit drains, complete-cache publication, and transaction rechecks.
`invalidate()` is immediate presentation/network fencing, not durable sign-out or proof of server revocation.

## Exact new API (all internal, package data.sync.session)

Names, constructor argument names, enum values and callable signatures below are fixed for independent tests.
Implementation helpers are private. Use immutable validated values and fixed/redacted displays.

```kotlin
enum class RecoveryRoomPhase { SIGNED_OUT, PREPARING, ACTIVE, TRANSITIONING }

data class RecoveryIdentity(
    val binding: ServiceSessionBinding,
    val operationId: String,
)

data class RecoveryRoomSnapshot(
    val revision: Long,
    val sessionGeneration: Long,
    val cachedAccountId: String?,
    val phase: RecoveryRoomPhase,
    val identity: RecoveryIdentity?,
    val baselineComplete: Boolean,
)

fun interface SessionRecoveryAuthority {
    suspend fun read(): RecoveryRoomSnapshot
}

enum class SessionRecoveryReason {
    CONFIGURATION_UNAVAILABLE, SIGNED_OUT, TRANSITION_REQUIRED,
    VAULT_MISSING, VAULT_UNAVAILABLE, IDENTITY_MISMATCH, BASELINE_INCOMPLETE,
    AUTHORITY_UNAVAILABLE,
}

sealed interface SessionRecoveryState {
    data object PrivacyLocked : SessionRecoveryState
    data class LocalOnly(val reason: SessionRecoveryReason) : SessionRecoveryState
    data class Locked(val reason: SessionRecoveryReason) : SessionRecoveryState
    data class Active(val session: RecoveredSession) : SessionRecoveryState
}

enum class SessionRecoveryFailure { INVALID_INPUT, STALE_SESSION, UNAVAILABLE }
class SessionRecoveryException(val failure: SessionRecoveryFailure) : RuntimeException

class RecoveredSession /* no externally usable constructor */ {
    val identity: RecoveryIdentity
    suspend fun <T> withAccessToken(request: suspend (BoundServiceAccessToken) -> T): T
}

class SessionRecoveryCoordinator(
    origin: ServiceOrigin?,
    vault: SessionVault,
    authority: SessionRecoveryAuthority,
    authApi: ServiceAuthApi,
    clockMillis: () -> Long = System::currentTimeMillis,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    val state: SessionRecoveryState
    suspend fun reconcile(): SessionRecoveryState
    fun invalidate()
}
```

### Value validation/security

- RecoveryIdentity operation and nonnull cached account are canonical lowercase UUIDs. ServiceSessionBinding already
  validates origin/account/positive generation. Snapshot revision and sessionGeneration are nonnegative Longs.
- SIGNED_OUT requires null identity and baselineComplete false; cachedAccountId may retain a private account.
- All other phases require identity, its binding generation exactly sessionGeneration, and cachedAccountId exactly the
  identity account. PREPARING/TRANSITIONING may have either baseline flag; only ACTIVE+complete is visible eligibility.
- Invalid constructor/copy inputs throw SessionRecoveryException(INVALID_INPUT), fixed message, null cause, disabled
  suppression. Do not echo identifiers or supplied text. All new model/state/handle/coordinator displays are fixed and
  redacted (closed enum reason/failure is allowed). Equality of snapshots covers every field; no credential in them.
- No credentials, proofs, paths, network bodies or account IDs in logs/exceptions. No arbitrary content caps.

### Startup algorithm and outcomes

Constructor performs no I/O, network, UUID/clock work. Initial state is PrivacyLocked. One owner contains one refresh
coordinator (when configured), one shared refresh mutex, and at most one currently authorized handle/lease. Reconcile
has a short synchronous state lock only; never hold it over suspending reads/network. Every reconcile starts a fresh
opaque process-local operation fence, immediately invalidates the previous handle/lease, and publishes PrivacyLocked.
`invalidate()` does the same synchronously/idempotently, without waiting for any read/network/mutex. No durable counter
is assigned or incremented here. Concurrent reconciles: newest start wins; older completion/error cannot publish or
invalidate a newer handle. A superseded call throws SessionRecoveryException(STALE_SESSION).

Use this precedence after opening the fresh fence (never examine vault before authority eligibility):

1. Null origin: publish LocalOnly(CONFIGURATION_UNAVAILABLE); no authority/vault/clock/network/UUID call.
2. Read authority. Non-cancellation read failure: Locked(AUTHORITY_UNAVAILABLE), preserve all data.
3. SIGNED_OUT: LocalOnly(SIGNED_OUT), no vault/network call; orphan active/prepared vault is never adopted.
4. PREPARING/TRANSITIONING: Locked(TRANSITION_REQUIRED), no vault/network call; R09 must recover privately.
5. ACTIVE whose identity origin differs from configured origin: Locked(IDENTITY_MISMATCH), no vault/network call.
6. ACTIVE with incomplete baseline: Locked(BASELINE_INCOMPLETE), no vault/network call.
7. Read vault. Null record (empty or positive tombstone): Locked(VAULT_MISSING). Any non-cancellation load failure:
   Locked(VAULT_UNAVAILABLE). Never clear, repair, regenerate keys, or interpret exceptions as deletion authority.
8. Record binding or activation operation differs: Locked(IDENTITY_MISMATCH). Matching PREPARED record:
   Locked(TRANSITION_REQUIRED) even with complete ACTIVE Room. Promotion across a crash belongs to R09.
9. Matching ACTIVE record: reread authority, reload vault, reread authority again before publishing. Both authority
   observations must equal the original snapshot exactly, including revision/cache/baseline; vault reload must equal
   original revision and all original record fields, including exact pending operation/key/body/token. Do not rely on
   ServiceRefreshAttempt referential equality: semantically identical restored instances with exact same text are equal
   for this check. Read errors map to the corresponding Locked reason; changed snapshot => Locked(IDENTITY_MISMATCH).
   No automatic reconciliation loop or adopting a new generation/record in this call.
10. Under the short lock recheck the operation fence/caller and publish Active with one handle whose authority predicate
    binds the exact Room snapshot and whose refresh lease binds record origin/account/generation/operation/ACTIVE.

Every seam return/error and final publication rechecks actual caller cancellation first and process fence second.
Cancelled current reconcile leaves PrivacyLocked, no handle; a newer call/state is never reset by an older cancellation.
An injected CancellationException is propagated as the identical instance within the actual caller if still active;
otherwise actual caller cancellation dominates. Do not use sleeps, raw cause unwrapping or recovery-disabling tricks.

Startup never reads clocks or classifies freshness: a matching complete account remains eligible for offline visibility
even when access/refresh is expired. Active means scope eligibility, not a usable credential or successful sync. Expiry,
pending-first exact replay and foreground-reauth latching remain the accepted refresh coordinator's responsibility.
An uncertain pending predecessor is retained even when predecessor refresh expiry looks old.

### Runtime authority and minimal R04b seam extension

Extend SessionRefreshLease constructor with final optional parameter:

```kotlin
private val authorizationCheck: suspend () -> Unit = {},
```

Add `internal suspend fun checkAuthorization()`; retain synchronous atomic checkAdmission/invalidate behavior. This new
check verifies actual caller activity, permanent invalidation, invokes the predicate, and rechecks activity/invalidation
on return/error. It is independent of the reauth latch so late safe failures are not incidentally replaced by reauth.
Injected cancellation remains cancellation; unexpected non-cancellation predicate failure maps to
SessionRefreshException(UNAVAILABLE); explicit SessionRefreshException remains its closed category. No raw causes.

SessionRefreshCoordinator invokes checkAuthorization in its existing checkAdmission and postSeam paths, and checks atomic
admission again after a successful authorization check before admitting work. Thus authority is checked before refresh
transmission, after every successful storage/clock/UUID seam, before grant CAS, and after seam results/errors. Existing
no-op default is compatibility for accepted primitive callers/tests only; R04c always supplies a real authority predicate.
Do not rewrite rotation, retries, codecs, capture, or CAS semantics. An already-started synchronous CAS may commit after
invalidation; dead leases cannot admit its result, and future R09 tombstone/revision fencing still provides durability.

RecoveredSession's predicate checks captured process fence, reads authority, requires exact original snapshot equality,
and rechecks caller/fence on all exits. Predicate mismatch permanently invalidates that handle, publishes
Locked(IDENTITY_MISMATCH) only if still current, and throws SessionRefreshException(STALE_SESSION). Authority error locks
as AUTHORITY_UNAVAILABLE and throws SessionRefreshException(UNAVAILABLE) at the predicate boundary. Permanent lease
invalidation may surface STALE_SESSION to the in-flight caller; the published Locked reason retains the authority-fault
category. It never silently adopts new authorization.

RecoveredSession.withAccessToken delegates to the ONE owner refresh coordinator using its private shared lease. No
public lease/refresh-coordinator getter, direct bearer-selection shortcut, or externally constructible usable handle.
Old handles after invalidate/reconcile throw SessionRecoveryException(STALE_SESSION) before I/O. In-flight stale work
may surface SessionRefreshException(STALE_SESSION); caller cancellation dominates either category. Original safe
SyncApiException/Vault exceptions remain closed and propagate under a current identity. Only authority/fence failure
locks visibility; transient/auth/legacy malformed network errors alone do not erase or hide valid offline account data.

Reuse existing withAccessToken retry logic (at most one unchanged access-401 retry). Guarded transport callbacks are
checked against Room authority both immediately before invocation and after success/error, with current caller/fence
first; do not return a stale result/error. No state lock/Room transaction spans HTTP. Refresh serialization mutex may
span refresh HTTP, but invalidate is synchronous and nonblocking. Already-admitted bytes cannot be recalled. Later
Room result application must independently recheck durable authority inside its transaction.

## Contributor source separation and ownership

Worker READ: AGENTS.md; this spec; docs/README.md, coordinator-workflow.md, architecture.md, testing-strategy.md,
sync/implementation.md, sync/integration-design.md, remaining-work-roadmap.md; shared contract/product/catalogue docs;
`app/src/main/java/com/dragosfotache/gymtracka/data/sync/` production sources; build/dependency files for context only.
Worker WRITE ONLY these production paths relative to that sync directory:

- session/SessionRecoveryModels.kt (new)
- session/SessionRecoveryFailures.kt (new)
- session/SessionRecoveryCoordinator.kt (new)
- session/SessionRefreshLease.kt (minimal predicate extension)
- session/SessionRefreshCoordinator.kt (minimal admission/post-seam calls)

Worker MUST NOT inspect tests/fixtures/reports, QA work/diffs, or broad repository diffs including test code. LSP operations
must be file-scoped within read allowlist. No build/test/report inspection without an explicit coordinator slot.

QA READ: same normative documentation/shared contract; existing `app/src/test/` test sources/fixtures and dependency/
build files. NO production declarations, source/diffs/decompiled code, Worker work, or LSP navigation into production.
Use supplied API facts below; report missing signatures to coordinator, never inspect implementation.
QA WRITE ONLY these new paths under `app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/`:

- SessionRecoveryCoordinatorTest.kt
- SessionRecoveryTestFixtures.kt
- SessionRecoveryAuthorizationTest.kt

QA uses existing QA fixtures where useful, or independent new bounded fault doubles. Do not modify existing assertions.
No shared build files, Android tests or docs changes assigned. Coordinator alone integrates harness and reviews both
layers. All shared-output Gradle commands need explicit serialized slots; compiler diagnostics may reveal only signatures
needed for tests, not authority to inspect implementations. No agent may commit/push, change config, touch backend/personal
data, run device/Google actions or broaden ownership. Fresh native sessions use Pi Muse Spark 1.3 Contributor/high.
Reports are asynchronous: `herdr agent prompt gym-coordinator "R04c Worker/QA: ..."` WITHOUT --wait; no agent wait/polling.

## QA-supplied accepted API/harness facts

All production types in this section are in data.sync.session unless qualified; JVM tests can use internal types.
JUnit4 + kotlinx-coroutines-test are available. No dependency or build change needed.

- `http.ServiceOrigin.parse(String): ServiceOrigin`, immutable equality; HTTPS only.
- `http.ServiceSessionBinding(origin, accountId: String, sessionGeneration: Long)` is immutable data class, generation >0.
- `http.BoundServiceAccessToken(binding, token: api.OpaqueToken)` exposes binding/token, redacted.
- `api.OpaqueToken(value: String)` nonempty opaque text, value equality.
- `api.ServiceAccountRef(accountId: String, displayName: String?)`.
- `api.ServiceSession(account, accessToken, accessExpiresAtMillis: Long, refreshToken, refreshExpiresAtMillis: Long)`
  validates millisecond range [-62167219200000,253402300799999], NOT freshness or lifetime ordering.
- `api.ServiceAuthApi`: suspend challenge(purpose: GoogleChallengePurpose = SIGN_IN): ServiceChallenge;
  exchange(GoogleExchangeAttempt): ServiceSession; refresh(ServiceRefreshAttempt): ServiceSession;
  logout(OpaqueToken): Unit. Only typed refresh is called by this task; other methods should fail if unexpectedly used.
- `api.ServiceRefreshAttempt.fromJson(idempotencyKey: String, requestJson: String)` retains exact valid original refresh
  body text; `.capture(key, token)` captures once. Properties: idempotencyKey/requestJson/refreshToken.
- VaultSessionPhase PREPARED/ACTIVE; VaultPendingRefresh(operationId: String, attempt: ServiceRefreshAttempt).
- VaultSessionRecord(binding, operationId: String, phase, session, pendingRefresh: VaultPendingRefresh? = null), immutable
  data class. Pending token must equal grant refresh token. Snapshot(revision: Long, record: VaultSessionRecord?) is named
  VaultSnapshot; nonnegative revision; revision zero requires null record.
- SessionVault suspend load(): VaultSnapshot; compareAndSet(expectedRevision: Long, record: VaultSessionRecord?):
  VaultSnapshot. Clear writes positive revision tombstone; no revision-zero reset. VaultFailure values INVALID_RECORD,
  UNREADABLE, UNSUPPORTED_VERSION, UNAVAILABLE, CONFLICT, REVISION_EXHAUSTED; SessionVaultException(val failure).
- SessionRefreshFailure INVALID_INPUT/STALE_SESSION/ACCESS_UNAVAILABLE/UNAVAILABLE; SessionRefreshException(val failure).
- api.SyncApiException.AuthenticationRequired(), ReauthenticationRequired(), MalformedResponse(), Transient(retryAfterMillis:
  Long? = null) are closed failures; retry hint bounded in milliseconds. Auth/legacy malformed failures never authorize cache deletion.
- Existing fixtures `RefreshTestFixtures`, `FakeSessionVault`, `FakeServiceAuthApi`, `realVault`,
  `InMemoryVaultFileStorage`, `IdentityVaultCipher`, `assertFailsWith` are QA-owned and may be read/reused. Real vault has
  constructor EncryptedSessionVault(storage: VaultFileStorage, cipher: VaultCipher, ioDispatcher = Dispatchers.IO,
  mutex = Mutex()); storage read(): ByteArray?, replace(ByteArray), cipher seal/open(ByteArray): ByteArray. Recreate over
  same storage to prove actual codec/CAS restart. Identity cipher is test-only, not a crypto-security claim.
- Optional actual strict HTTP legacy boundary: http.HttpServiceAuthApi(origin, transport: ServiceHttpTransport);
  FakeServiceHttpTransport fixture queues `.queueJson(status, body)`; `RefreshTestFixtures.legacyGrantBody()` carries
  independently specified incompatible expiry text. No real backend needed/authorized in this slice.

## Independent acceptance cases

Mandatory tests are observable; do not couple to incidental read counts except explicit no-I/O precedence and gates.
Use virtual-time/deferred gates, not sleeps. Assert no writes/network during reconcile, private cached identity never
becomes signed-out import data, and full preserved pending identity/body/token plus expiry values where relevant.

1. Initial/constructor PrivacyLocked and no seam activity; configuration unavailable local-only with zero activity.
2. Signed-out empty/retained cache/orphan vault => local-only, never inspect/adopt vault; preparing/transitioning => locked.
3. Active incomplete baseline and wrong configured origin => locked without vault/network access.
4. Empty/tombstoned vault, unreadable/unsupported/missing-key/unavailable => locked; no repair/writes/HTTP.
5. Matching active+complete => one usable handle; every account/origin/generation/operation/phase mismatch fails closed.
6. Active Room+matching prepared vault requires R09 recovery, no promotion; prepared Room never exposes credentials.
7. Matching expired grants => offline Active without clock/network; runtime expiry requires reauth as accepted, no erase.
8. Exact restored pending replay after reconstructing real encrypted vault and recovery coordinator, including body
   whitespace/escapes, expired predecessor, expired-access replay followed by at most one new rotation; original grant
   committed before new refresh; no new uncertain key on malformed/transient/cancel/restart.
9. Storage/authority changes between startup reads cannot publish; equal full restored pending body instances may pass.
10. Cancellation/errors at each startup seam, final fence, overlapping reconciles/newest wins, synchronous invalidate
    during blocked read; old failures/cancellations cannot lock a newer handle.
11. Old handle after reconcile/invalidate refuses before I/O; authority changes before request/refresh, during refresh,
    before commit, during load/CAS and after callback success/error fence results. Handle remains permanently dead even
    if original authority observation later reappears. No network under wrong origin/generation/account.
12. Logout-boundary races: invalidate immediately while refresh/transport/CAS blocked, then simulate R09 vault tombstone
    or B replacement through existing CAS. Late A grant/result cannot reinstall over newer revision or be returned;
    no claim invalidate itself durably clears either store. Cancellation after commit may leave grant/pending durably
    preserved and still no visibility under dead handle.
13. Concurrent requests share lease/rotation, one unchanged 401 retry; authority invalidation before retry blocks it.
14. Reauth, offline/transient, legacy strict malformed replay preserve cache eligibility and vault exact uncertain data;
    no blanket revocation, rounding or implicit sign-out. Returned token binding exact, original safe failures preserved.
15. Validation/copy/overflow-range inputs, redacted toString/message/cause/suppression, unexpected authority faults closed.
16. Accepted R04a/b tests unchanged and green. App wiring/schema/asset/security boundary unchanged.

Coordinator gates: focused new JVM suite, all vault/refresh regression cases, minimum Kotlin compile, full offline JVM,
Android-test compilation, debug assembly and lint as appropriate. Force reruns for acceptance and inspect nested XML
names/counts/failures/errors/skips. Device/backend/live gates are not newly claimed; no new Android-platform behavior
is introduced. Run both diff checks and check all changed links/untracked files. Final review records actual evidence,
durable R09 obligations, legacy limitation and explicit stop. At acceptance extract durable facts, archive this spec/review,
update roadmap/handoff, and STOP; no automatic R05 or app activation.
