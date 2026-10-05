# R08b — Bounded frozen-snapshot HTTP download validation

## Authority, goal and exact stop

Owner-released R08b follows accepted R08a and R05–R07 under
[coordinator workflow](../../coordinator-workflow.md). Implement one inert explicitly constructed driver which actually
calls `GymSyncApi.snapshot` outside Room, stages complete pages on the SAME already admitted `PushRun`, and returns
honest bounded transport/storage progress. Only the driver's actual fenced push `Empty` authorizes its initial download.

STOP after driver/tests and declared gates, independent coordinator review, owning docs and archived evidence/spec,
then scoped local acceptance commit and fresh coordinator transfer. No app construction/activation, automatic scheduling,
full-account graph validation, canonical replacement/baseline/absence/publication, last-success, R08c/R09/R10/R12,
new owner/store/API/codec/schema, token parsing, background work, retry/delay, automatic stage cleanup, credentials or
outbox rewriting. Downloaded is not bootstrap, convergence or visible account state. No push/deployment/private config/
live Google/account/personal-device action. Escalate unresolved requirements/gates/models to owner.

## Authoritative requirements and existing inventory

Read only applicable sections, following concrete invariant dependencies:

- [Implementation](../../sync/implementation.md#frozen-snapshot-staging-storage), shared push-first ownership, bounded
  incremental pull and API/secure HTTP; [design §4–5](../../sync/integration-design.md#5-frozen-snapshots-and-full-resync).
- Shared [service protocol frozen snapshots](../../../shared-docs/docs/backend-contract/service-protocol.md#frozen-sync-snapshots-and-lightweight-list-reads-t2t8),
  failure table and retention boundaries; [sync full resync](../../../shared-docs/docs/backend-contract/sync.md#cursor-retention-and-full-resync);
  [client snapshot consistency](../../../shared-docs/docs/backend-contract/android-client-protocol.md#snapshot-consistency).
- OpenAPI `GET /v1/sync/snapshot` and `SnapshotResult`/referenced upsert types, not unrelated routes. Snapshot limits
  1..500/default200, exact opaque nonempty baseline/continuations (whitespace preserved). Frozen lifetime24h from creation,
  non-sliding; invalid/foreign/wrong-context is NOT authentic expiry. Later-page dependencies must stage; ordering is
  no integrity shortcut. Whole nondeleted owned aggregates only; no permanent account/content/history cap.
- [Testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates), coroutine/instrumentation lessons,
  supplied AGENTS.md and full workflow before delegation. Existing strict legacy accepted-auth replay remains unresolved:
  preserve original uncertain IDs/bytes; never round/rewrite/rekey or infer revocation/deletion/cleanup.

Room11 and exported1–11 stay unchanged; bundled source DB stays v1. R08a stage tables are isolated from canonical/domain
storage. The application does not construct these sync drivers. `PushStore(database, session, REQUIRED owner)` admission
uses one application-owned `PushRunOwner` per physicalDB across the entire network interval. API/store/run must already
bind the same authorized service/account/session. Driver adds no admission/Mutex/owner and never releases/reacquires.

Verified existing seams (these facts are supplied to QA without production source access):

```kotlin
class PushRunOwner
class PushStore(database: GymDatabase, session: RepositorySession, owner: PushRunOwner) {
    suspend fun <T> withRun(expectedCacheEpoch: Long, block: suspend (PushRun) -> T): T
}
// PushRun has NO public constructor; operations require inherited admitted coroutine context.
suspend fun PushRun.beginSnapshotStage(stageId: String, pageLimit: Int = 200): SnapshotStage
suspend fun PushRun.readSnapshotStage(): SnapshotStage?
suspend fun PushRun.checkSnapshotStage(stage: SnapshotStage)
suspend fun PushRun.appendSnapshotPage(stage: SnapshotStage, page: SnapshotResultModel): SnapshotStage
suspend fun PushRun.abandonSnapshotStage(stage: SnapshotStage)
```

Above methods are actual members, not new extensions. `SnapshotStage` has no public constructor/copy; referential
capability identity and run's current token matter. Exposed immutable fields: `stageId:String`, `pageLimit:Int`,
`baselineCursor:String?`, `nextPageToken:String?`, `downloaded:Boolean`, `pageCount:Long`, `recordCount:Long`,
`canAppend:Boolean`; display fixed `SnapshotStage`. Fresh begin binds exact source-state presence/full cursor/last-success,
observed baseline flag, account/session/epoch/fixed limit. Begin requires ANY selected-account outbox EXISTS=false.
Capture AFTER begin is allowed and unchanged; append never demands a clean outbox again.

Append deep-owns via existing codecs before suspension; checks complete retained evidence and source/origin/live fences;
commits whole records/header/tokens atomically; returns fresh capability only on returned commit and active resumption.
Duplicate root `(type,id)` across pages (even identical), changed baseline, recycled continuation and nonterminal empty/
oversize pages fail. Invalid incoming page/progression throws `SyncModelException` before storage writes; retained
corruption/owned SQLite uses `SnapshotStageStorageException`; exact token/source changes use
`StaleSnapshotStageException`, session/epoch use existing stale fence exceptions. No graph/physical collision arbitration.
Write attempts retire tokens before first write; rollback/uncertain commit never restores them. Only durable reread can
recover, returning NONappendable observation; explicit exact valid-stage abandon and NEW UUID/null-token start are required.
Corrupt stages remain preserved/block ordinary push/pull. Downloaded stage also blocks. No automatic cancellation cleanup.

`PushDeliveryLoop(store, api, nowMillis).deliver(run, maxMutations)` is the admitted overload, default/max100.
Actual Empty is fenced head emptiness; LimitReached never probes a successor even when last ack emptied queue.
`PushDeliveryResult` fields `acknowledgedMutations:Int`, `stop:PushDeliveryStop`; stop variants Empty, LimitReached,
Deferred, Blocked, AuthenticationRequired, ReauthenticationRequired. ALL non-Empty outcomes return push-only.
Selected durable stage makes push return `Blocked(reason=PushBlockReason.SNAPSHOT_STAGE,errorCode=null)` before head
selection; driver start therefore returns push-only (`download=null`), not `SnapshotStageActiveException`.
Existing incremental `SyncDeliveryLoop.deliver` is unchanged; this task does not automatically route its recovery signal.

`GymSyncApi.snapshot(pageToken:String?, limit:Int=200):SnapshotResultModel` uses exact supplied token and fixed limit.
`SnapshotResultModel(records:List<SyncChangeModel>, baselineCursor:String, nextPageToken:String?, hasMore:Boolean)`.
`decodeSnapshotResult(encodeSnapshotResult(page))` synchronously validates and deep-owns full nested aggregates; immutable
strings remain exact. Shape errors are `SyncModelException`/`JSONException`. Existing adapter/refresh owns auth policy;
driver must not retry, replace decoration, reinterpret typed failures or create credentials.

## Normative new API

All new declarations below are in `data.sync.engine`. Names/signatures/fields are binding contracts for independent QA.
Use trailing commas and fixed/redacted displays. No data-class automatic display of tokens, bodies or failure codes.

```kotlin
const val MAX_SNAPSHOT_DELIVERY_PAGES = 100 // per invocation, NOT per stage/account
class SnapshotDeliveryLoop(
    store: PushStore,
    api: GymSyncApi,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun start(
        run: PushRun,
        stageId: String,
        maxPushMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
        maxPages: Int = MAX_SNAPSHOT_DELIVERY_PAGES,
        pageLimit: Int = 200,
    ): SnapshotStartResult
    suspend fun continueDownload(
        run: PushRun,
        previous: SnapshotDownloadResult,
        maxPages: Int = MAX_SNAPSHOT_DELIVERY_PAGES,
    ): SnapshotDownloadResult
}
class SnapshotStartResult internal constructor(/* immutable values */) {
    val push: PushDeliveryResult
    val download: SnapshotDownloadResult? // null for EVERY push non-Empty
}
class SnapshotDownloadResult internal constructor(/* immutable values + private provenance */) {
    val stage: SnapshotStage // exact latest returned stage, not a durable resume grant
    val committedPages: Int // THIS invocation's returned commits only
    val committedRecords: Int // THIS invocation's whole records in returned commits only
    val stop: SnapshotDownloadStop
}
sealed interface SnapshotDownloadStop {
    data object Downloaded : SnapshotDownloadStop
    data object LimitReached : SnapshotDownloadStop
    data object RestartRequired : SnapshotDownloadStop // authentic SnapshotExpired ONLY
    class Failed(val failure: SyncApiException) : SnapshotDownloadStop
}
```

Result constructors are internal, no copy/public fabricator; continuation carries originating driver referential identity
in addition to existing stage capability. This is a thin driver composition result, NOT a new storage owner/capability or
publication proof. `continueDownload` accepts ONLY a LimitReached result issued by THIS driver; others throw
`IllegalArgumentException` before HTTP/effects. Existing stage/run checks reject stale/foreign/superseded/closed/outside-run
capabilities. Do not accept a durable recovery observation or reconstruct a continuation from `readSnapshotStage`.
No standalone raw-stage download method. Caller invokes sequentially inside ONE uninterrupted `store.withRun`:

```kotlin
store.withRun(epoch) { run ->
    val started = loop.start(run, freshStageId, maxPages = 1)
    var progress = started.download
    while (progress?.stop == SnapshotDownloadStop.LimitReached) {
        progress = loop.continueDownload(run, progress, maxPages = 1)
    }
    // Caller policy only; no production engine loop/activation is introduced here.
}
```

MaxPages1..100/maxPush1..100/pageLimit1..500 and canonical lowercase stage UUID validate strictly BEFORE any clock,
Room or HTTP effects. Never clamp budgets, accept zero, parse token age or turn 100 pages into a permanent stage ceiling.
Invalid start arguments must not push/freeze existing intent. Continue validates budget and result eligibility before
any effects. Driver/string/result/Failed displays use fixed safe type names only.

## Sequencing, fences and failure ownership

1. `start` validates all arguments, cancellation, runs existing admitted bounded push overload on SAME run. Non-Empty:
   return push-only, never begin/read/check/download/abandon or pull. Exceptions propagate. Empty: begin NEW stage with
   fixed limit then paging core. Begin independently closes capture races; new intent before begin fails/preserves intent.
   Existing selected stage blocks push; start must not silently read/abandon around it.
2. Core never calls durable stage read. Current stage chains ONLY from begin/returned appends or bound continuation
   result. Before each HTTP call: ensureActive -> exact `checkSnapshotStage` -> ensureActive. Fetch exact
   `stage.nextPageToken` (null ONLY initial) and `stage.pageLimit`; outside Room under uninterrupted owner.
3. Immediately after API returns and cancellation check, synchronously deep-own/validate ENTIRE page before ANY
   suspending post-response fence. Use existing snapshot codec round trip; own nested lists, not merely envelope.
   Validate fixed count/nonempty nonterminal before commit. Capture malformed verdict, THEN exact stage check and
   cancellation check before returning Failed(MalformedResponse). No malformed/tail/expiry/failure may bypass fence.
4. Valid owned page is appended through existing member. Its cross-page root/token/baseline and strict retained checks
   are reused, not duplicated in an invocation-limited set. Model/progression exceptions FROM append propagate unchanged
   (not typed API failures); storage/fence/cancellation/unexpected errors propagate. No broad catches around commit or
   callbacks. Earlier pages survive. A failed/uncertain write has no returned commit/count and requires durable reread,
   not success inference from old token or header similarity.
5. Increment counters ONLY after append returns fresh token; check cancellation. A terminal committed page wins even
   on final allowed page: Downloaded, zero extra request/probe. Nonterminal at budget: LimitReached with latest exact
   appendable stage and bound same-run result. Continuing uses same pinned baseline/limit/token history, no begin/push/
   reread and no total page/record ceiling. Returned Downloaded proves actual validated terminal transport/page commit
   for this uninterrupted invocation chain ONLY, not complete account graph/baseline/absence/publication/last-success.
6. Catch `SyncApiException` ONLY from actual `api.snapshot` call: cancellation -> exact stage check -> cancellation before
   result. Authentic `SnapshotExpired` maps RestartRequired; retains stage/pages for explicit valid abandon/new UUID/
   null restart, NEVER silently abandons or begins. Every other subtype (including CursorExpired/wrong-context supplied
   by a test double, invalid snapshot token, auth, transient, unknown permanent, too-large, malformed) is Failed with
   original failure instance/category. No resend/delay/cleanup. Unexpected exceptions/cancellation propagate unchanged.
7. Results only summarize successful returned commits in this invocation and exact current stage. A failed call preserves
   latest valid capability, but continuation accepts only LimitReached: explicit failed/expired recovery policy stays
   outside driver. A new admitted run can only read/abandon, never continue old results. Run close/cancellation does not
   delete anything. Cancellation after synchronous storage commits cannot prove rollback.

Preserve all domain/outbox/watermarks/canonical checkpoint/baseline/last-success/device/catalogue/other scopes, except
explicit legitimate pre-download push effects and deliberately allowed newer capture. Compare preservation AFTER those
allowed operations. Earlier push acknowledgments and committed pages remain durable after later failures/reopen.
Publication's future returned epoch must be respected by R08c; R08b invokes no publisher.

## Independent files and supplied harness facts

Worker read allowlist: this spec, supplied AGENTS, relevant doc/contract sections above; production files ONLY under
`data/sync/engine`, `data/sync/store`, `data/sync/api`, `data/sync/codec`, `data/sync/model`, `data/sync/http`,
`data/sync/session`, and exact Room files `data/gym/GymDatabase.kt`, `SyncSnapshot{Dao,Entities}.kt`, `SyncDao.kt`,
`SyncEntities.kt` as needed. No tests/fixtures/reports/QA files or their diffs/navigation. Worker write allowlist ONLY
`app/src/main/java/com/dragosfotache/gymtracka/data/sync/engine/SnapshotDeliveryLoop.kt` and `SnapshotDeliveryResult.kt`.
No changes to existing seams; report missing contract before widening.

QA read allowlist: this spec, supplied AGENTS, relevant doc/contract/testing sections above; existing test files ONLY:
`data/gym/GymDatabaseTest.kt`; `data/sync/{PushClaimTestSupport,PushOutcomeTestSupport,PushAcknowledgmentTestSupport,
PullCheckpointTestSupport,PullDeliveryTestSupport,SyncDeliveryTestSupport,SnapshotStageTestSupport,SnapshotStageTest,
SnapshotStageFenceTest,PullDeliveryTest,PullDeliveryFenceTest,SyncDeliveryTest}.kt` in androidTest; JVM
`data/sync/http/{BoundSyncHttpApiTest,SyncHttpEndpointNetworkTest}.kt`. QA may read own new files. NO production source/
declarations/diffs/decompiled code, Worker files or navigation into them. Use published API facts here; ask coordinator
for missing signatures. QA write ONLY androidTest `data/sync/SnapshotDeliveryTest.kt`, `SnapshotDeliveryFenceTest.kt`,
`SnapshotDeliveryTestSupport.kt`. No edits to existing tests or weakened assertions. Coordinator owns integration/docs.

Concrete reusable test facts:

- `GymDatabaseTest` protected `database`; real in-memory Room before/after each test. `claimSession(account=CLAIM_ACCOUNT_A)`;
  `database.claimPushStore(session=claimSession(), owner=claimOwner())`; `claimOwner():PushRunOwner`.
- `database.stageSeedBaseline(cursor=STAGE_SYNC_CURSOR,lastSuccessMillis=STAGE_LAST_SUCCESS,account=CLAIM_ACCOUNT_A,
  cacheEpoch=0,baselineComplete=true)`; `stageSeedSecondScope`; `stageSeedOutboxPending`; seed helpers preserve other scopes.
  `claimExec(sql,args)` executes literal SQL; `claimSnapshot` snapshots all app tables; `stagePriorSnapshot` excludes ONLY
  three staging tables. Preservation oracle after allowed seed/capture/admission.
- `stagePage(records,baselineCursor,nextPageToken,hasMore)`, `stageWorkoutUpsert`, `stageRoutineUpsert`, `stageCustomUpsert`,
  `stageDelete`, `stageWorkoutEntry` and mutable-page builders are independent QA fixtures. Fresh valid UUID fixtures,
  whole large-note record available. `stageRecordRows(stageId)`, `stageTokenRows(stageId)`, `stageRowsFor(account)` expose
  read-only DAO rows for evidence; all are in permitted support. SQL stage columns documented in support.
- Reopen: `claimFileDatabase(context,name)` and unique-name helper; close/delete only test-owned DB. Existing executor
  gates for whole-input ownership in permitted fence tests; separate transaction/query executors, UNDISPATCHED child
  inheriting live run context; await actual captured transaction runnable then mutate before release. No sleeps.
- Script fresh `GymSyncApi` records snapshot `(token,limit)` calls, asserts `!database.inTransaction()`, permits gated
  callbacks/typed failures/unexpected/cancellation, delegates push script as needed. No fake DAOs/store/admission.
- Adapter composition representative may use permitted `BoundSyncHttpApiTest` scripted OkHttp `Call.Factory` pattern with
  independently handwritten JSON. Existing `HttpGymSyncApi(binding,provider,transport)`,
  `ServiceSessionBinding(ServiceOrigin.parse("https://example.com"), wireAccount,CLAIM_GENERATION)` where `wireAccount`
  is canonical lowercase UUID such as `a111b222-c333-4d55-8e66-f77788889999`. Existing `CLAIM_ACCOUNT_A` is `account-a`
  (Room-only fixture), NOT a valid HTTP binding. Seed Room stage/device/cache/session with the SAME `wireAccount`,
  `BoundServiceAccessTokenProvider { binding -> BoundServiceAccessToken(binding,OpaqueToken("test-only")) }`,
  `OkHttpServiceTransport(binding.origin, scriptedCallFactory)` signatures are available in that test harness.
  Do not connect external example.com; factory intercepts all requests. Exact URL query/request/body and returned terminal
  stage are independent oracles. Existing JVM covers actual local-TLS HTTP snapshot; do not duplicate network matrix.

## Risk-selected tests and gates BEFORE delegation

Risk: sync paging/ownership/fencing/recovery, no schema/security/config/dependency/activation change. Distinct new obligations:

1. Initial null request then exact opaque (including whitespace) continuation/fixed limits; whole multi-family pages,
   later dependency, valid empty terminal/account, terminal on budget; prior tables unchanged, no baseline/last-success.
2. Boundaries: strict preeffect inputs (including invalid UUID BEFORE push); nonterminal budget no probe; same-run repeated
   budget continuation across more than100 total pages to prove no permanent cap; stale/wrong-driver/non-LimitReached
   results rejected; no durable restart adoption.
3. Push-first: representative actual accepted ack followed by fenced Empty and snapshot on same run; retained/failure/
   auth/rejection/corrupt/oversize/deferred/budget never starts snapshot; existing exhaustive push-stop coverage reused.
   Retained stage blocks ordinary push/start. New intent between Empty/begin rejects; capture after begin preserved.
4. Whole-page deep ownership before suspending post-response check (nested mutable list); invalid complete page means
   zero page commit; cross-page baseline/root/token/cycle failures preserve earlier commits via existing storage validation.
5. Typed authentic expiry versus invalid/wrong-context/auth/transient/unexpected; exact instance in actual caller; no
   cleanup/retry, earlier commit survives. Late data/empty terminal/malformed/failure/expiry all fenced by representative
   session/epoch/full source changes, not exhaustive Cartesian products.
6. Queued/HTTP cancellation and stale responses; shared physicalDB owner held across network/continuations; commit fault/
   post-write cancellation demonstrates retirement and honest reread; file reopen preserves earlier page and old visible
   state, observation never appendable, explicit abandon NEW UUID/null request. Reuse existing R08a retirement matrix,
   add driver integration representative rather than duplicate migrations/primitive matrix.
7. Production HTTP adapter composition representative as above proves actual snapshot request/response connects to Room
   staging, fixed query and terminal state, without live backend or replacement adapter. Redacted result displays.

Iteration: narrow Kotlin/Android-test compilation and failing/affected cases only; coordinator grants EVERY Gradle slot.
Contributors initially have NO Gradle slot. Completion/blocker reports via async `herdr agent prompt gym-coordinator`,
never --wait/agent wait/acknowledgment polling. No shared builds concurrently.

Mandatory acceptance: `:app:compileDebugKotlin` (KSP), `:app:compileDebugAndroidTestKotlin`; fresh executed full offline
`:app:testDebugUnitTest --rerun`; fresh focused real Room new SnapshotDeliveryTest and SnapshotDeliveryFenceTest
(one class filter per Gradle command) plus existing SnapshotStageTest/SnapshotStageFenceTest/PullDeliveryTest/SyncDeliveryTest
for affected ownership/stage/push regressions; inspect actual nonempty XML names/counts/failures/errors/skips, not shell
success alone. `git diff --check` in BOTH repos; full scoped production/test/new-file review; asset/schema/old-entity
preservation. No gate substitute if unavailable.

Deferred broad gates: full unfiltered instrumentation, explicit assembly/lint/APK and applicable isolated real backend
at R08c R08 package acceptance and R10 integration, brought forward if actual scope changes create packaging/security/
schema/cross-feature risk. No new backend operations required R08b. API24/hostedCI/live Google/device/two-store convergence/
activation remain later authorized milestone obligations. Existing full R08a/JVM HTTP coverage is reused, not claimed new
R08b execution. Schema changes are forbidden; unexpected need is owner blocker, never omitted migration gate.
