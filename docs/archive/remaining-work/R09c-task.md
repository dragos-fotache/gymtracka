# R09c — private prepared-session engine admission and bootstrap coordination

## Authority, goal and exact stop

Ownership released under the standing coordinator workflow. Deliver ONE inert explicitly constructed coordinator that
freshly admits a matching private PREPARING Room / PREPARED vault pair, reuses R08 complete snapshot establishment and
R07 actual valid-tail bookkeeping under uninterrupted physical-DB ownership. Stop at a private canonical baseline and
bounded actual tail, never convergence, projection publication, vault promotion, ready/ACTIVE writing, repository/UI
rebinding or production construction. Room12 and the bundled asset remain unchanged.

No occupied-credential reauthentication, legacy import/delete/reassignment, account switching/sign-out, new Google
adapter, scheduling, live service/configuration or personal device/account work. Pre-existing local roots still refuse
preparation through R09a; no legacy disposition is selected. R12a and core R10 still precede live activation. Private
canonical writes do not authorize ordinary account repositories or publishers.

## Authoritative inputs and inventory

- AGENTS and docs/coordinator-workflow.md govern independent roles, serialized Gradle, review and scoped commit/rotation.
- docs/sync/implementation.md: durable private Room authority; prepared vault/Room coordination; encrypted vault/refresh;
  shared push-before-pull/valid tail; R08 staging, delivery and complete-baseline boundaries.
- docs/sync/integration-design.md §§4–6 and §7 barrier/activation prerequisites.
- shared-docs/docs/backend-contract/sync.md account, push/pull, full resync and preservation rules;
  service-protocol.md pull/snapshot boundaries; authentication.md Android session storage/recovery;
  android-client-protocol.md snapshot/auth handoff; openapi.yaml SnapshotResult and existing auth/sync shapes.
- docs/testing-strategy.md proportional coverage/execution cadence and instrumentation/coroutine lessons.

RoomSessionAuthority.read strictly validates journal/device/cache in one transaction. PREPARING observations intentionally
report baselineComplete=false even after cache establishment. Its reservation emits device activeAccountId=null,
cachedAccountId=target, generation=reserved, cacheState="preparing"; no ACTIVE writer exists.
SessionPreparationCoordinator.recover alternates Room/vault/Room/vault/Room, compares full grant and exact semantic pending
operation/key/body/token and returns advisory PreparedSessionObservation(room,vaultRevision), not admission.
SessionRefreshCoordinator plus SessionRefreshLease(PREPARED, authorizationCheck) already support exact pending-first
rotation/unchanged access-401 retry, origin/account/generation/operation/phase fencing and cancellation precedence.
SessionRefreshingGymSyncApi(coordinator,lease,transport) creates tagged HttpGymSyncApi per attempt; reuse it unchanged.

PushStore(database, RepositorySession, shared PushRunOwner).withRun(epoch) currently requires ACTIVE device identity.
PushRun owns handling mutex, claim/check/ack, checkpoint/batches/tail and stage operations. SnapshotDeliveryLoop.start
runs actual push Empty then bounded download; continueDownload requires its own same-run LimitReached; complete requires
its own actual terminal issuance and commits canonical/baseline/stage removal at epoch+1. PushRun adopts returned epoch.
SyncDeliveryLoop currently admits its own run; extract an internal admitted-run overload, retaining public behavior and
validation. That overload must reuse the actual PullDeliveryLoop.pullWithTail callback and run.recordPullTail.

## Concrete API contract

New internal session types (no public app APIs):

```kotlin
class PreparedSessionEngineCoordinator(
    database: GymDatabase,
    origin: ServiceOrigin,
    vault: SessionVault,
    authApi: ServiceAuthApi,
    transport: ServiceHttpTransport,
    runOwner: PushRunOwner,
    clockMillis: () -> Long = System::currentTimeMillis,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    fun invalidate()
    suspend fun bootstrap(
        stageId: String,
        maxSnapshotPages: Int = 100,
        snapshotPageLimit: Int = 200,
        maxPushMutations: Int = 100,
        maxPullPages: Int = 100,
        pullPageLimit: Int = 100,
        restartRetainedStage: Boolean = false,
    ): PreparedBootstrapResult
}
class PreparedBootstrapResult {
    val cacheEpoch: Long
    val retainedStage: Boolean
    val snapshot: SnapshotStartResult?
    val baseline: SnapshotBaselineResult?
    val delivery: SyncDeliveryResult?
}
enum class PreparedEngineFailure {
    INVALID_INPUT, NOT_PREPARED, IDENTITY_MISMATCH, STALE_OPERATION,
    AUTHORITY_UNAVAILABLE, VAULT_UNAVAILABLE,
}
class PreparedEngineException(val failure: PreparedEngineFailure) : IllegalStateException
```

Types/displays fixed/redacted, no secret-bearing causes. Result construction is internal and conveys no reusable authority.
Constructor performs no I/O/clock/UUID/HTTP. bootstrap validates canonical stage UUID, positive maxSnapshotPages (no 100
upper cap on aggregate invocation budget), snapshot limit1..500, push budget1..100, pull pages1..100, pull limit1..200
BEFORE effects. Chunk snapshot budget into existing <=100-page driver calls, chaining SAME-driver/SAME-run continuations.
There is no permanent account-size cap; caller can supply a larger positive invocation budget. Exhaustion returns the
latest snapshot LimitReached and retains stage; another invocation is recovery, not continuation.

The coordinator owns a single preparation reader and single shared refresh coordinator/mutex for this Room/vault pair.
Require ONE application-owned runOwner per physical DB shared with all other drivers; no default/new private run owner.
After successful input validation, bootstrap creates a newest opaque invocation fence; invalid input does not retire a
healthy invocation. Invalidation/new invocation immediately retires old process
admission/lease without waiting for owner/Room/HTTP. Queued calls recheck newest status after owner admission. Invalidation
is synchronous/idempotent and does not cancel/undo already-started writes or perform durable cleanup.

## Admission and transactional authorization

1. Freshly recover through R09b. NotPreparing/NeedsGrant/Blocked do not admit HTTP or Room writes. Preserve existing
   owned preparation exceptions, mapping to closed new categories where needed. Cancellation propagates identically
   within an active caller; actual caller cancellation precedes stale/error mapping.
2. Capture exact PREPARING observation and freshly reread both stores, matching the returned vault revision and full
   grant/pending semantic equality. Do not infer authority from caller-constructed observation/result or prepared label.
   A race after final read cannot be ruled out; independently fence around subsequent seams.
3. Create an internal exact private Room permit bound to SAME database instance, origin/account/generation/operation/
   journal revision and current invocation. Coordinator privately registers the exact issued permit reference after
   stable admission; permit currentness requires referential membership, not a caller-supplied always-true predicate.
   Unregistered constructor/factory candidates cannot admit. It is used only by explicitly constructed prepared
   PushStore/PushRun paths.
   No public optional predicate/default may bypass ordinary ACTIVE checks. Implementation may add an INTERNAL factory
   and thread an explicit permit into checkCanonicalFences and applyOwnedCanonicalBatch; no coroutine-global permissive
   context, raw account/generation-only shortcut, device ACTIVE write or ordinary repository permit.
4. Every private run transaction checks exact strict journal/device identity and current process permit inside the
   transaction BEFORE effects and AFTER writes before commit. Require device active=null, cached=target, generation
   exact and cacheState="preparing". Reuse RoomSessionAuthority's strict observation, including canonical origin and
   corruption refusal; do not build a weaker journal reader. Cache epoch retains existing exact checks and advances only
   from returned committed baseline. No vault/HTTP/clock work inside Room transactions, no fake two-store atomicity.
5. Lease authorization checks strict captured Room authority/current invocation around refresh seams and transport
   admission/results. Vault record identity is independently fenced by the existing refresh coordinator; same-session
   legitimate rotation may advance vault revision/grant and must not falsely invalidate the run. Nonmatching phase/
   identity, missing/tombstone/unreadable/change-racing stores fail private; no credential repair/cleanup/promotion.
   Finally retires the matching issued permit/lease without retiring a newer invocation.
6. Private prepared runs cannot invoke publishProjection: reject before barrier/visible writes. CanonicalStore,
   ProjectionStore, GymRepository and ordinary PushStore remain ACTIVE-only and cannot use private authority. No new
   private repository or local capture path. Explicit permit is threaded only through the composed PushRun writers,
   including push acknowledgment and incremental complete-batch writer, not default standalone canonical writers.

## Bootstrap, recovery and stop results

Hold the ONE runOwner continuously from fresh admission/durable epoch observation through all stage/push/snapshot/
completion/pull/tail work. No release/reacquire or nested withRun. Durable selected cache epoch is read coherently under
strict private authority INSIDE that owner slot, then the same prepared run admission checks it again without another
owner acquisition. Implement an internal prepared admission entry sharing existing withRun lifecycle machinery; do not
read epoch outside owner ownership or nest/reacquire withRun. HTTP stays outside Room.

- Read selected durable stage through existing validated observation-only seam. If present and restartRetainedStage=false,
  return retainedStage=true and no snapshot/baseline/delivery/HTTP. Never adopt a retained terminal for completion.
- restartRetainedStage=true explicitly authorizes abandon of ONLY that exact valid stage under CURRENT authority and
  start with supplied NEW stageId/null token. Reject equal retained stage UUID; no automatic abandonment on any failure.
  An explicitly abandoned retained stage forces a fresh snapshot even if an older complete baseline/checkpoint remains;
  do not reinterpret explicit restart as permission to skip the replacement snapshot. Corrupt retained stage remains
  preserved; invalid/foreign transport tokens do not imply restart/cleanup.
- Without eligible complete baseline/checkpoint, use SnapshotDeliveryLoop.start after real push Empty. Nonempty/error/
  budget push stops return its summary only; no pull/baseline/publication. Download failure/expiry/budget returns latest
  snapshot stop with stage retained; no in-call retry/restart. Terminal alone invokes this driver's complete.
- Durable complete baseline AND valid checkpoint with no stage skips snapshot, including dirty post-crash baseline;
  it is canonical recovery, not published/ready/ACTIVE. Reuse existing checkpoint validation rather than infer from stage
  disappearance or a thrown prior result. Missing baseline/checkpoint always requires actual snapshot, never null pull.
- After returned completion, or eligible durable-baseline recovery, invoke the R07 admitted shared delivery overload on
  SAME run/returned epoch. It again observes actual push Empty before pull; only its actual validated terminal callback
  records last-success. Snapshot establishment itself preserves last-success. Pull stop/expiry remains an explicit R07
  result, not an in-call bootstrap/retry loop. Bounded tail/outstanding=false is NOT convergence/publication/activation.
- Return cacheEpoch reflecting only known durable returned completion/current-run epoch; retained result summaries are
  nonsecret progress, never promotion authority. Result loss/cancellation may follow synchronous commit: reread stores
  and cache/stage/checkpoint under fresh admission. No compensation, implicit fencePrivate, discard of uncertain grants,
  generation restart, request rekeying or outbox rewrite.

## Independent ownership / permitted files

Worker production READ: app/src/main/java/com/dragosfotache/gymtracka/data/sync/**; data/gym/GymDatabase.kt,
Sync*Dao.kt, Sync*Entities.kt, FeatureRepositories.kt, GymRepository.kt solely for persistence/fence dependencies.
Worker WRITE: new session/PreparedSessionEngineCoordinator.kt, PreparedSessionEngineModels.kt,
PreparedSessionEnginePermit.kt; existing session/RoomSessionAuthority.kt (narrow private transaction check only),
store/PushStore.kt, CanonicalValidation.kt, CanonicalStore.kt; engine/SyncDeliveryLoop.kt.
No tests/fixtures/QA reports/diffs/test build outputs. No schema/DAO/entity/repository changes. Request any missing source
seam/allowlist/API change asynchronously; do not silently widen.

QA READ/WRITE: new androidTest/.../data/sync/session/PreparedSessionEngineTest.kt,
PreparedSessionEngineFenceTest.kt, PreparedSessionEngineRecoveryTest.kt and optional PreparedSessionEngineTestSupport.kt.
QA READ: existing androidTest/.../data/sync/session/**, androidTest/.../data/sync/*Test*.kt harness/fixtures,
test/.../data/sync/session/** and test/.../data/sync/http/** harnesses. No production source/declarations/diffs/Worker work.
QA accesses production APIs only as supplied contracts and compiler diagnostics; coordinator supplies missing signatures.
QA may add focused JVM tests only after coordinator supplies a concrete pure-boundary contract/allowlist.
Both read this spec and its task-relevant authoritative sections, not unrelated archives/system maps. Coordinator owns
shared build/harness/configuration, final review and docs. No contributor Gradle until an explicit serialized slot.
Reports fire-and-forget to gym-coordinator, never --wait, agent wait or acknowledgment polling.

## QA harness facts

New APIs above live in `data.sync.session`. Supply a deterministic clock with live access expiry: for the QA grant expiring
at 1_700_000_000_000, use clockMillis={ 1_699_999_900_000L }. An empty outbox yields local push Empty with ZERO push HTTP;
initial empty bootstrap's only requests are snapshot then pull, not empty push frames.

Actual adapter requests: GET `/v1/sync/snapshot`, initial query [("limit","200")], continuation query
[("pageToken",exactToken),("limit",fixedLimit)]; GET `/v1/sync/pull`, query [("cursor",exactCheckpoint),("limit","100")];
body=null and headers exactly {"Authorization":"Bearer <selected access>"}. Nonempty push is POST `/v1/sync/push`, same
bearer header and exact mutations body; empty head never transmits. Request paths are relative; origin is tagged/fenced
in ServiceSessionBinding, not a ServiceHttpRequest property. Empty pull nextCursor MUST equal supplied cursor; use
r09c-baseline on an empty post-snapshot tail. Nonempty complete batches can advance it.

Minimal valid nonempty snapshot record shape (literal IDs/names/revisions are independent fixture choices):

```json
{"aggregateType":"routine","aggregateId":"a1000001-1111-4111-8111-111111111111","operation":"upsert","revision":1,"record":{"id":"a1000001-1111-4111-8111-111111111111","name":"Old cloud draft","isDraft":true,"revision":1,"exercises":[]}}
```

Place one such record per records array: page1 with baselineCursor="r09c-baseline", nextPageToken="r09c-page-2",
hasMore=true; page2 with a distinct root ID and same baseline, nextPageToken=null, hasMore=false. Empty draft exercises
need no catalogue seed. A pull terminal may contain an upsert of the first draft at revision2/name="Changed cloud draft":
`batches:[{batchId:<canonical UUID>,cursor:"r09c-tail",changes:[<whole matching revision2 upsert>]}],nextCursor:"r09c-tail",hasMore:false`.
No production encoder may generate expected wire bodies. Assert both snapshot roots, updated first canonical record,
exact checkpoint/last-success and untouched visible domain graph.

Existing result field contracts: SnapshotStartResult(push:PushDeliveryResult,download:SnapshotDownloadResult?);
download(stage,committedPages:Int,committedRecords:Int,stop) counts THIS returned chunk only; stops Downloaded,
LimitReached,RestartRequired,Failed(failure). SnapshotBaselineResult(cacheEpoch:Long,baselineCursor:String,pageCount:Long,
recordCount:Long,hasOutstandingLocalWork:Boolean). SyncDeliveryResult(push,pull:PullDeliveryResult?,
hasOutstandingLocalWork:Boolean?); push(acknowledgedMutations:Int,stop); pull(committedBatches:Int,stop) with
Tail,LimitReached,RecoveryRequired(reason),Failed(failure). A terminal snapshot is not a tail; DB last-success must prove
actual tail bookkeeping. These summaries are never permission to bypass fresh fences.

Retained rejected heads stop locally before push HTTP or payload parsing; a pending head receiving an actual rejection
is a different fixture. Unexpected transport exceptions are sanitized by the existing refresh/HTTP seams, not guaranteed
raw-message propagation. For committed-baseline lost-result evidence, gate pull after actual snapshot completion, inspect
complete dirty cache/canonical/checkpoint with null last-success, then invalidate/cancel return; recover from those durable
rows without redownload. Merely ignoring a returned successful tail is not lost-result evidence.

Ordinary ProjectionStore acquires its required barrier before entering its ACTIVE-fenced transaction; PREPARING refusal
therefore acquires/closes one test lease without visible writes. Private PushRun rejects publication before its barrier,
but the coordinator intentionally exposes no private-run callback; final coordinator review verifies that guard without
adding test-only production access. An AFTER-page-write rollback preserves the earlier committed empty stage header;
an AFTER-canonical-baseline-write rollback preserves previously downloaded stage/pages. Neither fault rewinds earlier
commits or authorizes cleanup. Expected-failure async children must be supervised so their intended exceptions do not
cancel the parent test's independent assertions or newest invocation.

## Risk-selected verification

High-risk new private authorization across composed engine transactions, no schema/security packaging changes.
Distinct new Room/scripted actual transport tests (independent literal JSON wire expectations and stored rows):
- Actual R09a reservation + R09b matching prepared vault admits empty/representative nonempty multi-page account; exact
  bearer/origin/limits/tokens; complete canonical baseline then actual tail writes last-success, private journal/device
  unchanged, no visible graph/publication or ACTIVE/promotion. Old unchanged roots discovered by snapshot, not null pull.
- Existing complete dirty baseline/checkpoint skips redownload and reaches actual tail; no baseline/result-stage-absence
  success inference. Selected stage refuses HTTP unless explicit valid abandon/new UUID restart; corruption preserved.
- Missing/nonmatching vault/Room origin/account/generation/operation/phase and empty/tombstone fail before sync HTTP.
  Representative corrupt authority and process invalidation/newest queued invocation fail closed without cleanup.
- Pending-first exact refresh key/body and fresh full-grant CAS before sync transmission; same-session rotation permitted;
  missing/mismatched vault midflight and late Room fence prevent returning/applying result. Cancellation precedence and
  uncertain committed vault return preserve exact identity. Reuse existing detailed vault/refresh matrix, not duplicate it.
- Shared-owner exclusivity through snapshot continuation/completion/tail, ordinary stores/repos/publisher still refuse
  PREPARING; retained rejected/frozen outbox stops obey existing policy, no successor bypass/rekey/drop.
- Snapshot page budget/failure leaves staged pages; no permanent <=100 page cap (representative continuation crossing a
  100-page chunk). Returned baseline epoch is adopted for actual tail. Newer outbox intent remains exact/protected.
- At least one AFTER-write journal/device tamper/abort/cancellation rolls back private canonical/stage transaction; durable
  authority replacement during network fences both safe failures and success. Earlier committed pages survive later fault.
- File Room reopen with same reservation/prepared vault recovers retained stage by explicit restart and complete dirty
  baseline without redownload; lost-result after committed baseline proven by durable reads, not result/token claims.

Reuse accepted R08 whole-graph/issuance/absence/collision tests, R07 exact-tail/last-success tests and R09a/b strict authority/
vault preservation tests. New integration checks must prove new authorization/composition, not reenumerate model fields.
Iteration: coordinator-owned compile and affected tests. Acceptance: full fresh offline JVM; focused new Android classes
PLUS existing session package, SnapshotBaselineTest/FenceTest, SnapshotDeliveryTest/FenceTest, SyncDeliveryTest/FenceTest,
PushAcknowledgmentTest/FenceTest and PushClaimOwnershipTest. Execute each fully qualified class separately or a verified
package filter, inspect XML names/counts/failures/errors/skips. assembleDebug/lintDebug and APK secret/test-helper review
are brought forward for authentication integration. Asset/schema exact preservation and both diff checks required.
Full unfiltered instrumentation remains required at R09 activation-integration milestone BEFORE activation; real two-store
HTTP convergence is core R10, live barriers R12a, API24/real Google/OEM/cloud/live journeys remain their existing milestones.
These deferred gates are not this private canonical-only slice's acceptance claim. No backend gate declared here because
wire/transport unchanged and deterministic actual adapter integration plus existing HTTP/vault gates isolate the new risk.
Unavailable required contributor/device/test gates are blockers to escalate, never substituted or declared passed.
