# R08c — Complete canonical baseline, absence and publication recovery

## Authority, goal and stop

Owner release authorizes this dependency-ready slice after accepted R08b, under
[coordinator workflow](../../coordinator-workflow.md). This is the ONE normative specification.
Deliver inert complete-snapshot canonical establishment and coordinated protected publication/recovery over the
accepted single-owner pipeline. Production remains signed-out/local-only and constructs none of these components.

Stop after independent production/test review, the gates below, owning-document updates and archived acceptance
spec/review, then a scoped LOCAL acceptance commit and durable future-coordinator handoff documentation.
The owner's slice-specific stop overrides automatic rotation here: DO NOT start a successor coordinator or next-slice
work after R08c. Cleanup only owned resources and report the accepted stopping state. No push/deployment,
backend implementation/configuration, personal fixtures, Google/account/personal-device actions, import/replace or
sign-out authority, R09 journal/durable Room-vault authority, R10 convergence, R12 live edit/timer barrier, scheduler
or activation. Preserve accepted history and unrelated user work; never amend/rewrite.

## Binding sources and current inventory

Read only applicable passages/dependencies, not an entire documentation/contract/archive suite:

- [Design §§2–3](../../sync/integration-design.md#2-canonical-state-versus-local-intent-account-wide-protection-first)
  and [§5](../../sync/integration-design.md#5-frozen-snapshots-and-full-resync): canonical versus projection,
  reservations/revisions, complete frozen graph, baseline/absence and explicit discard/restart.
- [Implemented staging](../../sync/implementation.md#frozen-snapshot-staging-storage),
  [download](../../sync/implementation.md#bounded-frozen-snapshot-download),
  [canonical batches](../../sync/implementation.md#fenced-canonical-batches),
  [publisher](../../sync/implementation.md#protected-publication).
- Shared [sync full-resync/failure rules](../../../shared-docs/docs/backend-contract/sync.md#cursor-retention-and-full-resync),
  [snapshot consistency](../../../shared-docs/docs/backend-contract/android-client-protocol.md#snapshot-consistency),
  [name projection](../../../shared-docs/docs/backend-contract/android-client-protocol.md#exercise-name-projection),
  [frozen snapshots/identity reservations](../../../shared-docs/docs/backend-contract/service-protocol.md#frozen-sync-snapshots-and-lightweight-list-reads-t2t8),
  [domain rules](../../../shared-docs/docs/backend-contract/domain-rules.md): aggregate/reference/domain requirements.
  Applicable OpenAPI schemas: SnapshotResult, SyncUpsertChange and its three record variants, Workout/Summary/children,
  Routine/children, CanonicalCustomExercise/Exercise and referenced scalar types. Existing strict codecs/models remain
  the wire authority; do not create another encoder/parser/API.
- [Testing policy/cadence](../../testing-strategy.md#proportional-coverage-and-verification-gates),
  [isolated backend](../../testing-strategy.md#isolated-real-backend-procedure),
  [instrumentation/coroutine lessons](../../testing-strategy.md#instrumentation-and-coroutine-lessons).

Live accepted storage: Room version11, 20 entities (prior17 unchanged plus3 staging), exported1–11; source bundled v1
asset remains unchanged. No entity/schema/asset change is needed or authorized by this spec. If one becomes necessary,
STOP for coordinator/user resolution before changing it; version bump, preserving migration, KSP/export and every
supported start/copied-v1 gates would become mandatory.

Reuse `PushStore(database, RepositorySession(account, generation), REQUIRED shared PushRunOwner)`;
`withRun(expectedCacheEpoch) { run -> ... }` owns the physical DB across HTTP and coordinated commits.
`PushRun` already owns its handling Mutex and stage/checkpoint capabilities. Reuse
`beginSnapshotStage/readSnapshotStage/checkSnapshotStage/appendSnapshotPage/abandonSnapshotStage`, strict retained
staging validation, canonical family/revision/body helpers and the existing `ProjectionStore`.

`SnapshotDeliveryLoop(store, api, nowMillis).start(run, stageId, maxPushMutations=100, maxPages=100, pageLimit=200)`
and `continueDownload(run, previous, maxPages=100)` are accepted. Only actual fenced push Empty authorizes a new
download; other stops never authorize recovery around intent. Budgets1–100 are invocation limits, never permanent
account-size caps. Fixed record limit1–500, exact baseline/tokens/record keys, whole pages and whole aggregates persist.
Selected stages (including downloaded/corrupt) block ordinary push/pull. Capture AFTER begin remains allowed.
Only THIS driver's LimitReached capability continues in the SAME uninterrupted run. Reads/restarts never adopt
append or completion authority; explicit valid-stage abandon followed by NEW UUID/null-token restart remains policy.
Authentic SnapshotExpired requests explicit restart, not cleanup; invalid/foreign/wrong-context failures never do.
Earlier commits survive later failure. Failed/uncertain writes require durable reread; token retirement is not success.

## Public interface contract

Add the following without changing accepted constructor/start/continue signatures:

```kotlin
// engine/SnapshotDeliveryLoop.kt
suspend fun complete(run: PushRun, previous: SnapshotDownloadResult): SnapshotBaselineResult

// store/SnapshotBaselineResult.kt (new)
class SnapshotBaselineResult internal constructor(/* immutable backing values */) {
    val cacheEpoch: Long
    val baselineCursor: String
    val pageCount: Long
    val recordCount: Long
    val hasOutstandingLocalWork: Boolean
    // fixed redacted "SnapshotBaselineResult"; no public constructor/copy
}

// store/PushStore.kt, on the SAME PushRun
suspend fun publishProjection(projection: ProjectionStore): ProjectionPublicationResult
```

Worker may add focused internal baseline helpers and an internal `PushRun.completeSnapshotStage(stage)`; it must NOT
expose an unguarded public baseline writer or a storage-only downloaded observation as completion authority.

`complete` accepts ONLY an actually issued terminal Downloaded result from THIS driver, bound to the current exact
stage on this SAME admitted run. Generic stop/stage/result shapes are not authority: track actual terminal issuance
by private referential evidence, not just `stop == Downloaded`. Reject foreign driver, nonterminal/failure/restart stop,
recovery read observation, superseded token, wrong/closed/outside run and repeated completion. Eligibility checks occur
before Room/HTTP/clock effects. Conservatively retire terminal issuance on the first eligible completion attempt;
completion failures leave recovery to durable observation/explicit abandon/restart, never retry by fabricating results.
No HTTP, push/pull, retry, clock, cleanup-on-error or automatic publication occurs in `complete`.

`publishProjection` admits/rechecks the run, requires no enclosing Room transaction, verifies publisher pairing with the
SAME physical database/account/session (an internal nonmutating binding check in ProjectionStore is appropriate),
and serializes the entire publication invocation on the existing handling Mutex. It invokes the existing publisher
outside an enclosing Room transaction, allowing the required barrier's actual pause/drain before the publisher's own
transaction. No new owner/barrier or permissive lease is introduced. Reject pairing mistakes before lease acquisition.
The caller must pair API/store/run/publisher to the same already authorized service/account/session and hold ONE
application-owned owner per physical database; these inert seams do not establish durable authorization.

A successful baseline commit advances the run's expected epoch to the returned baseline epoch; a returned publication
advances it to `ProjectionPublicationResult.cacheEpoch` (PUBLISHED increments again). No old epoch is used for subsequent
run operations. Retire old checkpoint/stage capabilities before write attempts. Only successful commit/resumption may
install a new run epoch; failed/uncertain writes cannot guess it. Durable epoch changes make an old run fail closed.
A new admitted run using the actual durable epoch is the recovery path, not a swallowed failure/implicit readoption.

## Complete baseline transaction

Under the SAME admitted run/handling Mutex, in ONE fenced Room transaction:

1. Recheck current token referential identity, selected account/session/current epoch, exact full stage header and all
   strict retained record/token/page evidence. Require downloaded terminal phase, positive page count, null continuation
   and exact nonempty baseline. Explicitly recheck origin generation/epoch and original full source sync-state
   presence/cursor/last-success/baseline observation even though terminal tokens are nonappendable. Recheck cancellation
   and live admission around all write boundaries. No old-origin/read-observation adoption.
2. Deep-decode all staged whole canonical upserts with existing strict codecs. Build/validate the COMPLETE frozen graph,
   not each page's graph and not a union with old missing roots: custom references may be on later pages; bundled
   references must exist as immutable global nondeleted catalogue rows; relationship lookup IDs must exist; at most
   one active workout; routine/draft/finished/input invariants use existing validators. Empty graph is valid.
3. Load and strictly validate retained canonical evidence/counters/body identities and known family reservations.
   Validate all incoming frozen family roles/root/entry/set bindings against each other and retained evidence, including
   roots becoming absent. Workout/routine/exercise namespaces stay independent. Existing current/known bindings are
   reused; do not invent account-lifetime removed-child reservation storage.
4. Fail closed if an incoming frozen root revision is LOWER than an already observed retained revision; if equal,
   authoritative fields must agree with a retained upsert (only convenience exerciseName/additive unrepresented data
   differences are ignored). Equal/lower restoration of delete or snapshot_absent is not allowed. Higher revision can
   replace current observation subject to existing family rules. Do not silently ignore a staged member and then claim
   the frozen graph complete. Outbox/protection is not permission to mix an older frozen graph with newer canonicals.
5. Before the first write, retire the exact stage/checkpoint capabilities. Compute checked `oldEpoch + 1`; overflow is
   a fixed safe storage failure and changes nothing. Install every staged canonical root/body as a complete upsert at
   the new epoch, preserving exact retained local-generation watermarks (zero only for previously unknown roots).
   Existing equal-authoritative upsert may receive the validated staged frozen body/labels; no field merge/label upload.
   Mark every previously OBSERVED selected-account root missing from the complete snapshot `snapshot_absent`, clear
   its canonical body and retain its nullable/positive known revision without manufacturing a deletion revision.
   Missing `unobserved` reservations stay unobserved: an unsent creation is not a server deletion. Retain all ledger
   rows/watermarks; do not hard-delete evidence or fabricate canonical records from domain rows.
6. Atomically establish `baselineComplete=true`, `cacheEpoch=newEpoch`, `projectionDirty=true`, preserving the old
   materialized epoch. Create cache metadata if absent. Set the selected sync cursor to the exact frozen baseline,
   preserving source last-success EXACTLY (missing source creates state with null last-success). Download/baseline is
   not a post-baseline pull tail/convergence and does NOT set last-success. No other sync-state row/device/catalogue
   value is changed.
7. Delete exactly the completed selected stage with only its stage-child cascade. Recheck stage/children removal,
   canonical/cache/checkpoint outcomes, new-epoch/session/admission/cancellation and relevant affected-row counts before
   commit. Any graph/conflict/fence/storage/cancellation failure rolls ALL baseline/cursor/absence/cache/stage deletion
   writes back together. SQLite-owned errors have safe fixed messages/no raw causes; existing model/fence/cancellation
   provenance must not be sanitized into a transport stop.
8. Return `SnapshotBaselineResult` only after committed resumption, with durable stage page/record totals, exact baseline,
   new epoch and account outbox EXISTS observation in the transaction. Counts are not another authority. Update live
   run epoch only on this successful path. No visible domain rows/metadata change in this transaction.

Newer capture during download/completion is legitimate: do not require an empty outbox again, parse/filter around its
state or rewrite/drop/rekey uncertain work. Preserve exact IDs/payload bytes/errors/order/frozen grants, generations and
watermarks, including local creation/deletion and edits on unrelated roots. Other scopes and the bundled catalogue
remain unchanged. Physical local/foreign/bundled UUID collisions are a PUBLICATION limitation, not permission to remap
or corrupt independent canonical storage; baseline can be retained while visible publication is blocked/fails.

## Protected publication and recovery

- Direct `ProjectionStore.publish(epoch)` must fail closed on ANY selected durable stage before writing/advancing an
  epoch (including corrupt/downloaded stage). Foreign stages do not block selected publication. Keep existing outbox
  protection, incomplete-baseline/no-op semantics, safe errors, lease provenance and lease exactly-once closure.
- After complete baseline, the stage is gone, canonical baseline is complete/dirty at epoch+1 and visible projection
  remains its old/local graph. A caller may invoke `run.publishProjection(pairedPublisher)` under uninterrupted ownership.
  ANY selected outbox row means PROTECTED and the ENTIRE old/local graph stays unchanged; dirty/baseline checkpoint
  remain durable. No partial root publication. Outbox state/bytes/version/errors are irrelevant to protection.
- When clean, reuse the existing whole-graph/physical collision/family/parent/surviving-foreign-reference validation and
  explicit transactional materialization. Local/signed-out/foreign/bundled collisions fail without overwriting/remapping.
  Invalid lease, race capture/switch/epoch, SQLite failure or cancellation roll back visible work. Canonical baseline
  already committed remains durable if later publication fails. No automatic abandon or domain cleanup.
- An actual publication increments the epoch AGAIN, sets materialized epoch to it and clears dirty. Preserve cursor,
  last-success, canonical revision/body/state/row epochs, watermarks, unrelated scopes/catalogue and exact uncertain
  outbox work. Use the returned epoch, never the original baseline epoch. Same-run subsequent checkpoint reads/pull
  and old-callback fencing must reflect it. Complete-baseline pull/tail remains existing R07 behavior, not auto-routed here.
- Crash/uncertain completion: never use thrown/canceled results, retired tokens, stage absence or an old summary as
  proof this call committed. Reread actual durable stage/cache/canonical/checkpoint under current authority. If stage
  remains, it is observation-only and requires explicit valid abandon/new-stage restart, including downloaded stages.
  If baseline is durable and no selected stage remains, admit a NEW run at the durable epoch and reuse coordinated
  publisher; do not redownload merely to publish a known complete dirty baseline. This is durable baseline publication
  recovery, NOT transport-result readoption, last-success/convergence, import/sign-out or account activation evidence.
- Crash/uncertain publication: reread current durable epoch/baseline/dirty/materialized state; a fresh paired run/publisher
  revalidates it. Dirty complete baseline retries normal publication; clean equal-epoch retries return UNCHANGED with no
  new signal. Lease close failure after commit is not rollback proof; no inference about credentials/cache revocation.

## Role independence and allowed files

Both roles read this spec, supplied AGENTS, the applicable standards/contract passages above and full workflow.
No blanket repository/archive/OpenAPI ingestion. Missing facts are requested asynchronously from coordinator.

**Worker production WRITE allowlist:**

- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/PushStore.kt`
- new `.../store/SnapshotBaselineResult.kt`, new focused `.../store/SnapshotBaselineStorage.kt`
- `.../store/ProjectionStore.kt`
- `.../engine/SnapshotDeliveryLoop.kt` and `.../engine/SnapshotDeliveryResult.kt` only for issuance/completion contract
- `app/src/main/java/com/dragosfotache/gymtracka/data/gym/SyncCanonicalDao.kt`

Worker READ: those production files plus task-needed existing `data/sync/store/*.kt`, `engine/*.kt`, `model/*.kt`,
`codec/*.kt`, `mapping/*.kt`, `api/GymSyncApi.kt`, relevant `data/gym/Sync*Dao.kt`, `Sync*Entities.kt`, `GymDatabase.kt`
and root/domain entities; no UI/auth/lifecycle audit. Worker NEVER reads tests/fixtures/results/reports/QA source or diffs.
No changes outside WRITE allowlist; ask for necessary new seam before acting. No entity/schema/build/manifest changes.

**QA WRITE:** new `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/SnapshotBaselineTest.kt`,
`SnapshotBaselineFenceTest.kt`, `SnapshotBaselineTestSupport.kt` (focused independent Room graph/rollback/recovery tests).
QA READ: test sources under that `androidTest/.../data/sync/` package, exported schema11 and existing migration test
harness if needed, relevant JVM/backend test support only for independent harness facts. QA NEVER reads production
source/declarations/implementations/diffs/decompiled code or Worker output. LSP respects these boundaries.
Use compile/public API contracts in this spec, not production inspection; ask for missing signatures. Existing tests
are read-only. Coordinator owns integration/build/harness/doc/report changes and final review of both layers.

Harness facts (existing independent test support, available for reuse):

- Real Room in-memory `Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)`; file reopen helper
  `claimFileDatabase(context,name)`/`claimUniqueDbName(base)` for durable recovery; seed device/session with
  `claimSeedDevice(account=CLAIM_ACCOUNT_A,generation=CLAIM_GENERATION)` (generation7).
- `claimPushStore(session=claimSession(), owner=claimOwner())`; SHARE ONE owner per physical database in tests too.
- `stageSeedCheckpoint(cursor,lastSuccessMillis,account,cacheEpoch,baselineComplete)`,
  `stagePage(records,baselineCursor,nextPageToken,hasMore)`, `stageWorkoutUpsert(id,name,revision,exercises)`,
  `stageRoutineUpsert(id,name,revision)`, `stageCustomUpsert(id,name,revision,userCreated)`,
  `stageWorkoutEntry(id,exerciseId,setId,notes)`. Defaults valid active workout; use distinct IDs for two roots/children.
- `SnapshotDeliveryScriptApi(database, mutableListOf<SyncPushScript>(), mutableListOf<SnapshotScriptResponse>())`;
  pages `SnapshotScriptResponse.Page(stagePage(...))`, failures `.Failure(exception)`. It records exact outside-Room
  snapshot calls and fails unsolicited push/pull. Empty outbox uses actual Room Empty, not zero-request HTTP probe.
  `SnapshotDeliveryGatedApi`/CompletableDeferred for network capture/switch races.
- `r05cStore(account,generation,barrier)`, `R05cTestLease` (`revoke`, `closeCount`),
  `r05cGrantBarrier(lease,onAcquire)`, `r05cSeedCache/r05cSetCache/r05cReadCache`,
  `r05cQuery/r05cDump/r05cDumpAll`, `r05bSeedCanonicalRow`, independent JSON builders and literal row oracles.
  `claimSnapshot()` includes all tables; preservation oracles go AFTER allowed capture and BEFORE operation-under-test.
- Public read-only canonical/staging DAOs: `getRootsForAccount/getAccountCache/getRoot`,
  `getStageForAccount/getRecords/getTokens`; tests may use raw SQLite/schema11 for isolated seeding and rollback triggers.
  Expected bytes/values are independently authored, never production-encoder-derived or decompiled.
- Preserve structured child run context with inherited `coroutineScope { async/launch ... }`, not a runTest receiver's
  lost-context async. Expected throwing children are caught in their actual caller (or properly supervised), not allowed
  to cancel the parent accidentally. Query callbacks run BEFORE SQL: postwrite cancellation hooks fire on next query.

## Risk-selected tests and mandatory gates

High-risk account-wide persistence, absence and privacy/protection transaction. Distinct new obligations (combine
related representative cases, no test-count target; use existing strict-codec/budget/staging/physical-matrix coverage):

1. Actual-driver terminal completion with old unchanged root + later-page custom dependency + routine/draft, canonical
   baseline/absence installed without visible publication; source last-success exact, watermark/other-scope/catalogue
   preservation; equal authoritative/name-only replacement and stale revision/conflict rejection.
2. Valid empty account: known missing upsert/delete become snapshot_absent with known revisions retained; unobserved
   unsent reservation stays unobserved; old visible account disappears ONLY on clean publication; separate local/foreign
   rows remain. Missing checkpoint/cache bootstrap creates exact baseline but null last-success.
3. Complete-graph rejection (unresolved custom/global relationship lookup, multiple active, family root/entry/parent
   collision). Entire baseline transaction unchanged and stage retained; later-page references succeed.
4. Driver authority: storage-only terminal/read-restarted/foreign driver/run, nonterminal/failure/expiry and duplicate
   completion cannot establish baseline. Existing middle/final download failure/restart/switch/expiry tests are reused;
   add completion-specific source/header/session/epoch/corruption fences and redaction checks.
5. Newer concurrent captured edit after begin protects whole projection and exact uncertain identity/bytes; baseline
   still succeeds, publisher PROTECTED. Publication acquisition race capture protects; switch/lease fence fails. Use
   an actual capture helper, not only a fabricated outbox row; baseline must not lower its watermark.
6. Late baseline SQL failure/postwrite cancellation/checked epoch overflow rolls back canonical+absence+checkpoint+
   cache+stage deletion. Retired completion cannot retry; durable reread observes stage and explicit abandon/restart.
7. Successful baseline then publication failure/collision retains dirty complete canonical baseline/cursor and old
   visible graph. Fresh file-backed reopen/new-run recovery publishes without another snapshot; clean retry UNCHANGED.
   Separately prove LOST RESULT AFTER COMMIT, not just pre/postwrite rollback: hold the complete caller's resumption on
   a test-only queued dispatcher until its separately executed Room transaction runnable returns, inspect actual durable
   baseline/checkpoint/stage removal, cancel the caller before result delivery, then recover through a fresh durable-epoch
   run with no snapshot HTTP. A publisher lease.close failure after graph commit likewise requires durable epoch reread
   and fresh UNCHANGED retry, not rollback inference. Use actual executor/deferred barriers, never sleeps/polling.
   Lease closes exactly once; paired wrong physical DB/session rejects before barrier. Physical and surviving foreign
   reference collision behavior reuses publisher matrix plus representative integrated test.
8. Baseline epoch+1 then PUBLISHED epoch+2 is actually used by same-run checkpoint read/new work; older stage/checkpoint
   and old-epoch callers fail. Selected downloaded/corrupt stage blocks direct publication; foreign stage does not.

Iteration: coordinator reviews production before any QA execution; compile production/test Kotlin, then individual
new classes/failing methods on owned emulator. Reuse existing staging/driver/publication tests for unchanged matrices.
No role runs Gradle without an explicit coordinator-granted serialized slot. Coordinator may own ALL Gradle gates.
At acceptance every following gate is REQUIRED, no silent substitution/deferment:

- `:app:compileDebugKotlin` (KSP executes for changed DAOs), `:app:compileDebugAndroidTestKotlin`.
- Fresh FULL OFFLINE `:app:testDebugUnitTest --rerun` (unfiltered); inspect XML counts/names/failures/errors/skips.
- Fresh FULL UNFILTERED `:app:connectedDebugAndroidTest` on the test-owned emulator (no class/filter argument); inspect
  actual nonempty XML and successful task exit. Includes existing migrations/all supported starts/copied-v1 regressions,
  Room and Compose, not just new classes. A filtered/compiled/cached layer is not this gate.
- Explicit `:app:assembleDebug :app:lintDebug`; inspect lint XML and debug APK packaging for permissions/backup policies,
  excluded test/JDBC/backend helpers/credentials, source asset/schema preservation. APK presence alone is not assembly.
- FULL existing isolated `:app:testRealBackend -Pgymtracka.realBackendConfig=<owned ephemeral path>` (no test filter),
  exercising accepted actual HTTP snapshot/baseline/token/pull/reference/name/ownership interoperability alongside the
  package regression. No new backend source/suite/fixture bypass. Offline Room+HTTP tests do not substitute for it;
  real-backend JVM tests do not prove Room-backed engine convergence. Launcher script regression tests if harness changes.
- Both repository scope/diff checks, `git diff --check`, new-file whitespace and owning documentation link/anchor checks.

Gate availability verified BEFORE delegation: test-owned API37/Android17 emulator (37.2 system image) booted with
private Android/AVD home and existing SDK/KVM; default ADB server is preserved. Isolated launcher built/started the backend in disposable PostgreSQL
with generated test-only credentials; focused existing snapshot HTTP preflight executes (NOT final/full acceptance).
Concrete resources/slots/paths are coordinator-owned execution receipts, not contributor authority. Any unavailable
required gate/model/tool or unresolved scope/product/contract question goes to USER before continuing; no substitute,
skip, weakened oracle or gate. Kotlin LSP initializing is an allowed bounded-navigation fallback, not clean diagnostics.

API24, hosted CI, deployed TLS/phone/Google, two-store convergence, real live barriers/durable authorization and app
activation remain explicitly later milestone gates (R09/R10/R12/R13/R17/R18); no claim those passed here. Legacy strict
accepted-auth replay precision remains unresolved: NEVER round/rewrite/rekey original uncertain grants, infer broad
revocation or delete credential/cache state. All inherited security/persistence/source-independence safeguards apply.
