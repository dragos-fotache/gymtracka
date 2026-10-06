# R09d — inert completed preparation and exact vault promotion

## Authority, outcome and stop

Ownership was released for the next bounded R09 slice under coordinator-workflow.md. R09a/b/c and R08 are accepted
foundations. Deliver one explicitly constructed INTERNAL completion boundary for a fresh/no-legacy private preparation:
fresh actual R07 tail + complete projection and ACTIVE journal/device in ONE Room transaction, followed by exact
PREPARED→ACTIVE vault CAS, with durable recovery of the Room-committed/vault-unpromoted gap. ACTIVE here is a stored
label; no application construction, recovered handle, UI selection or repository rebinding is performed by completion.

Stop after independent scoped acceptance and durable documentation/commit. Production remains signed-out/local-only.
No occupied-credential reauthentication, imports, account switching/sign-out, journal cleanup, schema/entity changes,
new driver/vault, live Google/device/backend actions, configuration, pushes, scheduler, R10 convergence or R12 barriers.
Legacy local-only roots remain refused unchanged, including roots appearing after reservation. R12a and core R10 still
precede production publication/account activation. This slice uses required controlled test barriers, never a default
no-op. There is no permission to choose legacy disposition or activate the app.

## Authoritative reading

Supplied AGENTS; coordinator-workflow.md role independence; testing-strategy.md proportional gates/execution cadence;
sync/integration-design.md §§2 projection gate, 6 two stores/startup activation, 7 live barrier prerequisites;
sync/implementation.md protected publication, complete snapshot recovery, vault, private authority/preparation/engine.
Shared: product-behavior.md personal account scope; backend-contract/sync.md account model and pull/full-resync;
authentication.md Android storage/recovery; open-decisions.md P16. Existing codecs/HTTP semantics remain unchanged.
Expand safety-relevant reading but do not recursively ingest unrelated contracts.

## Current concrete seams

- Room12; sync_session_authority singleton id1: revision, session_generation, phase (signed_out/preparing/transitioning/
  active), service_origin/account_id/operation_id. sync_device_state: active_account_id, cached_account_id,
  session_generation, cache_state. RoomSessionAuthority.read() strictly reads SQLite types/extra singleton rows and
  tuples in a transaction. checkPreparedInTransaction(permit) checks exact origin/account/generation/operation/revision,
  active=null/cached=target/cache_state=preparing and permit.isCurrent(). PREPARING read deliberately reports no readiness.
- Existing generic DAO updateAuthority/updateDeviceState have exact whole-old-row predicates and affected counts.
  No new table or schema is needed. Completion increments checked journal revision ONCE, keeps reserved generation,
  origin/account/operation, writes phase=active and device active=cached=target/cache_state=ready. Preserve operation for
  exact restart recovery; no cleanup/clear or counter reset. Post-write full strict tuple comparisons are mandatory.
- PreparedSessionEngineCoordinator(database, origin, vault, authApi, transport, runOwner, clockMillis, uuidProvider)
  owns newest opaque invocation, registered concrete referential permit and refresh lease. bootstrap(stageId,...)
  stays canonical-only. Candidate permits cannot self-register. All existing ordinary stores and capture/repository
  paths remain unchanged ACTIVE-only. Public PushRun.publishProjection still refuses private runs before barrier.
- PushStore.withPreparedRun(permit,readEpoch,block) owns the caller-supplied ONE physical-DB PushRunOwner uninterrupted.
  Epoch read occurs inside owner admission. PushRun handlingMutex serializes composed writes. ProjectionStore(db,
  RepositorySession(account,generation), REQUIRED barrier) public publish remains ACTIVE-only; existing whole-graph/
  collision/generation/materialization logic must be REUSED, not duplicated. Existing ProjectionStore owns lease
  acquire outside Room and close in finally. ProjectionPublicationResult status PUBLISHED/UNCHANGED/PROTECTED/
  INCOMPLETE_BASELINE, cacheEpoch, materializedEpoch; actual publication increments epoch once.
- R07 SyncDeliveryLoop(store,api,clock).deliver(run,maxPushMutations,maxPullPages,pullPageLimit) performs actual push Empty
  then actual pullWithTail terminal callback -> run.recordPullTail. Existing summaries are NOT completion authority.
  PullTailCommitResult.checkpoint carries exact cursor/lastSuccessMillis/epoch/session/run; checkpoint is referential.
  Add only an INTERNAL optional callback to the admitted R07 overload if needed to capture its actual committed tail
  token privately. Do not infer tail from nonnull last-success, a returned/forged summary or caller-provided token.
- SessionVault.load() returns VaultSnapshot(revision,record?). compareAndSet(exactRevision,fullRecord) increments revision
  with overflow refusal. VaultSessionRecord(binding,operationId,phase,session,pendingRefresh) is immutable/copyable;
  VaultSessionPhase PREPARED/ACTIVE. Preserve full grant and exact pending operation/key/body/token on promotion.
  Semantic record equality must tolerate separately decoded pending attempts. CAS/load may commit despite uncertain
  or canceled returns; never erase, rekey, refresh, compensate, restart generation or infer rollback from an exception.

## Normative API (QA-visible contract)

Extend the existing INTERNAL PreparedSessionEngineCoordinator with:

```kotlin
suspend fun completePreparation(
    barrier: ProjectionPublicationBarrier,
    maxPushMutations: Int = 100,
    maxPullPages: Int = 100,
    pullPageLimit: Int = 100,
): PreparedCompletionResult
suspend fun recoverCompletion(): PreparedCompletionResult
```

Add INTERNAL PreparedCompletionStatus { COMPLETED, NOT_READY } and INTERNAL immutable PreparedCompletionResult
in com.dragosfotache.gymtracka.data.sync.session, with val status: PreparedCompletionStatus, val cacheEpoch: Long?,
val delivery: com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryResult?. SyncDeliveryResult exposes push,
nullable pull (terminal stop PullDeliveryStop.Tail), and nullable hasOutstandingLocalWork. Fixed redacted
string representation, no handle/credentials/reusable permit/constructor exposed publicly. COMPLETED means only stable
matching Room ACTIVE + vault ACTIVE reread for this operation at return. NOT_READY has no completion claim; delivery
may show actual bounded stop. No previous PreparedBootstrapResult or other caller-supplied progress is accepted.
Use existing closed PreparedEngineException/PreparedEngineFailure categories (extend only with closed new categories
if essential); cancellation has caller precedence and injected cancellation identity inside the active caller.
Readiness failures return NOT_READY; identity/race/corruption/I/O failures throw closed errors. No raw secret-bearing
cause/suppression/message. Newest completion/bootstrap/recovery invocation or invalidate retires old permits/leases;
invalid inputs fail BEFORE retiring valid work, I/O/barrier/HTTP/clock effects.

Implementation-private helper signatures may be settled by Worker within these invariants; no public authority seam
or generic arbitrary lambda may bypass the registered permit/actual-tail readiness provenance. Do not add constructors
or internals solely for coverage. Shared engine admission/error/settlement may be factored to avoid duplication while
preserving all R09c behavior. Coordinator must review that refactor and rerun its regressions.

## Completion transaction and two-store boundaries

1. Validate budgets (push/pull 1..100, pull limit 1..200). Use the same newest-invocation fencing as bootstrap. Freshly
   read exact PREPARING/PREPARED via R09b/fresh alternating admission, register concrete permit, lease and prepared run.
   Hold shared DB owner continuously through tail, barrier/publication, Room completion, vault CAS and final rereads.
   If already Room ACTIVE, perform recovery below without HTTP/barrier/redownload or another publication.
2. Selected retained stage or missing complete baseline/valid nonnull checkpoint returns NOT_READY, unchanged. Do not
   abandon/resume/re-download here. Otherwise run the existing R07 admitted push-before-pull/actual terminal-tail seam.
   Any rejection, pending retry, delivery/page budget, transport/auth stop, cursor-expiry or outstanding work prevents
   completion. Preserve typed actual stops. Tail token is captured privately from actual committed terminal callback.
3. After tail, retire the prepared refresh lease before promotion (no further HTTP/refresh). Fresh alternating durable
   Room/vault/Room/vault/Room reads require exact PREPARING identity/revision and matching PREPARED full record. Vault revision/grant
   may legitimately have rotated during SAME-session tail: pin the fresh post-tail record/revision, not admission's.
   Check Room/vault counter exhaustion BEFORE Room completion, since avoidable overflow must not create its gap.
4. Acquire required publication barrier outside Room, under same owner/handling lock. An explicit internal paired
   publication entry uses registered permit; public publication stays ACTIVE-only. Transaction rechecks permit,
   invocation, exact tail checkpoint full sync-state row (cursor and last-success), expected epoch, complete baseline,
   no selected stage, no selected outbox, no local-only roots, strict counters and lease validity. New intent at barrier
   returns NOT_READY without ACTIVE writes. Changed checkpoint/session/epoch or corrupt state fails closed.
5. REUSE existing whole canonical graph validation, collision checks, account-only replacement, generation/watermark
   preservation and materialization. PUBLISHED or already coherent UNCHANGED only can proceed. In THE SAME Room
   transaction change exact journal/device to ACTIVE/ready, retain generation/operation and increment revision once.
   Verify expected post-write journal/device/cache/epoch and readiness, lease/currentness and cancellation before commit.
   Every precommit graph/storage/count-zero/trigger-rewrite/lease/cancellation failure rolls back domain+cache+journal+
   device together. Cursor/last-success from actual tail remain unchanged by publication/completion. Catalogue/foreign
   scopes/local roots/uncertain intent stay preserved. No vault/HTTP/clock/UUID work inside Room.
6. After known successful Room return, invalidate prepared lease/permit usefulness for normal run work. Freshly reread
   ACTIVE exact new revision/identity, coherent complete materialization, no stage/outbox/local roots and PREPARED vault
   record; phase-only CAS at its exact pinned revision, copying full grant/pending bytes. Recheck invocation before and
   after all suspensions. CAS cannot replace mismatched/orphan/occupied unrelated state; no retry-over-conflict loop.
7. Both pre-CAS and final alternating Room/vault/Room/vault/Room rereads recheck coherent whole readiness: exact
   ACTIVE revision/identity and ready device tuple, clean materialization at expected epoch, full checkpoint row, no
   stage/outbox/local roots, and full vault record/revision equality. A stale observation must not start a new CAS.
   Operational barrier-acquire failures throw closed errors; explicit invalid leases may return NOT_READY after rollback.
   Final rereads establish stable exact ACTIVE pair before COMPLETED. Already-started
   Room/CAS writes can commit after invalidation/cancellation: dead invocation cannot return completion; preserve state
   and use recoverCompletion later. Room commit followed by lease.close failure is a retained completion gap, NOT
   rollback; do not promote from catch or reuse an old result as proof.

## Recovery

recoverCompletion performs no HTTP/refresh/clock/UUID/barrier/publication/domain/journal writes. Hold the same shared
owner (not a nested prepared admission); newest invocation fences plus a serialization mechanism must prevent overlap
with bootstrap/completion. Non-ACTIVE Room returns NOT_READY without vault promotion; preparing with an ACTIVE vault
never authorizes completed preparation. For strict configured-origin ACTIVE Room, require full ready device tuple,
complete materialized clean cache, valid nonnull checkpoint, no stage/outbox/local roots. Refuse corrupt/changed/
incomplete state and preserve it. A matching PREPARED vault may promote only its exact identity/operation/generation and
fresh revision via phase-only CAS. A matching ACTIVE vault is read-only idempotence (NO extra revision). Missing,
unreadable, mismatched or orphan credentials stay private/closed unchanged. Expired grant is binding evidence, not
freshness; promotion recovery preserves any exact pending refresh, never performs it. Alternating full durable rereads
must establish stable matching ACTIVE pair before COMPLETED. Keep origin and operation provenance; no automatic
cleanup or reconstruction. A newer fence/read/write result cannot be undone by an older failure.

This is inert preparation recovery, not general activated-account recovery after later user edits; the existing
SessionRecoveryCoordinator remains the future offline eligibility boundary. COMPLETED itself constructs no handle.

## Disjoint contributor ownership

Worker READ production only under app/src/main/java/com/dragosfotache/gymtracka/data/sync/ and directly required Room
DAOs/entities/GymDatabase under data/gym. No tests/fixtures/reports/QA output, generated sources or opposite-role diffs.
Worker WRITE only these sync files:
- session/PreparedSessionEngineCoordinator.kt, session/PreparedSessionEngineModels.kt
- session/RoomSessionAuthority.kt, session/PreparedSessionEnginePermit.kt
- store/PushStore.kt, store/ProjectionStore.kt
- engine/SyncDeliveryLoop.kt
- new focused session/PreparedSessionCompletion*.kt if necessary.
No DAO/entity/schema/build/UI/transport/other production edits without coordinator escalation.

QA READ/WRITE tests only. Read existing files under app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/session/
and ProjectionPublication*Test*.kt, SnapshotBaseline*Test*.kt in data/sync; related existing QA-only helpers as necessary.
Write new session/PreparedSessionCompletionTest.kt, PreparedSessionCompletionRecoveryTest.kt,
PreparedSessionCompletionFenceTest.kt, PreparedSessionCompletionTestSupport.kt; modify PreparedSessionEngineTestSupport.kt
only if needed without changing existing assertions. No production declarations/implementation/diffs/generated classes/
KSP/schema/decompiled sources/Worker output/LSP cross-role access. Ask coordinator for missing facts.

Coordinator owns spec/docs, integration and final production/test review, all build/harness/emulator resources, slot
serialization and preservation checks. No contributor Gradle without explicit slot. Reports asynchronously via
herdr agent prompt gym-coordinator, never --wait/wait/poll. Suitable existing panes receive FRESH native
meta/muse-spark-1.3-contributor/high sessions, not completed R09c conversations.

## QA harness facts and independent observable obligations

Reuse QA-only PreparedEngineFixtures/Harness/Transport/AuthApi (constructors and methods documented inside those test
files). Fixtures.engine(database,vault,auth,transport,owner,...), harness.reservePreparedPair, engine.bootstrap(stageId)
create actual preparation+baseline/tail; never seed a fabricated success summary. Snapshot/pull literals and deterministic
clock in support exercise actual client adapters. Empty snapshot: records=[], baselineCursor string, nextPageToken=null,
hasMore=false. Empty pull: batches=[], nextCursor SAME requested cursor, hasMore=false. Nonempty routine fixture helpers
provide real canonical/domain distinction. Transport.queueOk/body, queueCode, responseGate and serve allow exact requests,
cancellation/race gates. Harness creates real Room and Android Keystore/file vault in a test namespace; file create/reopen
and reopenVault survive recovery. Raw oracle helpers journalRow/deviceRow/accountCache/syncState/canonicalRoots/outbox
are independently expected. cache fields baselineComplete/cacheEpoch/projectionDirty/materializedEpoch. Normal fresh
reservation is revision1/generation1 preparing; successful completion is journal revision2/generation1 active and device
[target,target,1,ready], cache materialized clean at one extra epoch only if graph publication occurred. Journal
service_origin stores canonical scheme://host:effective-port, including HTTPS default :443 (fixture origin
https://sync.example.test:443), not the bare URL input. Vault promotion
adds ONE revision, phase only; repeat recovery adds none.

ProjectionPublicationBarrier is a fun interface with suspend acquire(ProjectionPublicationRequest) returning
ProjectionPublicationLease: fun checkValid(), fun close(). Request accountId/sessionGeneration/expectedCacheEpoch.
Bootstrap on an empty fresh pair consumes one empty snapshot then one empty pull; completion requires one ADDITIONAL
actual empty pull at the same cursor, no snapshot or push HTTP for an empty queue. Missing baseline/checkpoint, valid
retained stage, barrier-time intent and actual bounded driver stops return NOT_READY; malformed retained state/overflow/
storage/CAS conflict produce closed errors. Tests need not couple unspecified fault classes to new enum categories.
Use controlled gates/failing leases, no sleeps. Raw SQL query callbacks/triggers can inject AFTER-write cancellation,
RAISE(IGNORE), abort and valid-type wrong tuple rewrites. Verify actual hook fired and complete durable rollback.

Distinct obligations (proportionate representative tests, not combinatorial quotas):
- Actual empty + nonempty completion materializes latest actual pull graph and atomically establishes Room ACTIVE,
  promotes exact grant, preserves epoch/generation/provenance and actual tail row. Ordinary bootstrap remains private.
- Missing baseline/checkpoint and retained/corrupt stage cannot complete; no hidden snapshot/stage abandonment. Rejected/
  budget/HTTP stop and selected outbox protect preparation. New intent at barrier prevents completion; no empty-summary
  inference. Foreign/local physical collision and whole-graph failure preserve old graph and private authority.
- Room domain/cache/journal/device rollback together on representative post-write trigger/count-zero/lease/cancellation
  faults and overflow. Corrupt SQLite types/extra rows and valid-but-wrong tuple recheck refuse. No vault promotion
  follows failed or ambiguous publication return.
- File Room reopen + actual Keystore vault recover exact RoomACTIVE/vaultPREPARED gap from lease-close failure or vault
  commit failure. Recovery does not publish/tail again, is idempotent, preserves expired grant and exact pending bytes.
  PREPARING/vaultACTIVE, ACTIVE/missing/mismatched/unreadable and incomplete ready cache refuse unchanged.
- Gate late Room/vault reads, pending CAS and postcommit result delivery: newest invocation/invalidate/cancellation
  prevents stale success or overwrite/cleanup. An already-started commit may persist; reread recovery, never compensation.
  Show same shared owner serialization and candidate/no-public-path refusal. R09c regression remains intact.
- One legitimate post-tail same-session refresh rotation is promoted, not old admission revision. Pending recovery
  copied verbatim including request whitespace. Redacted results/errors/closed causes; no construction/app wiring.
Reuse R05/R08 graph/retained validation and R09a/b/c exhaustive foundational coverage; repeat only integration risks.

## Gates and evidence

High risk: new atomic publication/authority transition and credential phase CAS across two stores. Iteration compile
production + Android tests and affected focused tests; coordinator grants each serialized slot. Acceptance requires fresh
full OFFLINE JVM (`--offline :app:testDebugUnitTest --console=plain` with test tasks rerun, not all dependencies), ALL new
completion classes individually on test-owned API37/Android17 emulator, affected R09c three engine classes + R09b real
Room/vault session + R05 projection publication/graph + relevant R08 baseline/fence regressions. Inspect actual XML
names/counts/failures/errors/skips, not shell success alone. Assembly/lint/APK checks required for new security boundary.
No backend/Google/account/phone gate declared by this inert slice. Full unfiltered instrumentation and real two-store
HTTP convergence remain R09/R10 integration obligations BEFORE app activation; API24/hosted CI/live journeys remain
later declared milestone obligations. No schema changes: preserve runtime12/exported schemas/source asset, no migration
rerun implied. Run both git diff checks and documentation relative-link/anchor/new-file whitespace validation.
Evidence records actual commands/environment, executed tests, uncertainty and deliberately deferred gates in one review.
No success or completed task until required gates and independent final review pass; escalate unavailable gates/questions.
