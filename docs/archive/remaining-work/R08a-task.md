# R08a — Durable frozen-snapshot staging storage foundations

> Archived accepted specification. Current boundaries are in
> [sync implementation](../../sync/implementation.md#frozen-snapshot-staging-storage).
> This historical record grants no new authority.

## Authority, goal and exact stop

Owner explicitly released the fresh coordinator after completed read-only readiness following accepted R07c.
The [standing workflow](../../coordinator-workflow.md) governs independent contributors, local acceptance commit and rotation.
Deliver only a data-preserving staging migration and inert Room storage primitives on the existing admitted `PushRun`.
Stage complete frozen snapshot records OUTSIDE canonical and visible domain storage. A downloaded stage is NOT a
validated complete graph, canonical baseline, deletion/absence authority, bootstrap success or visible publication.

Stop after coordinator review, required gates, owning-doc/archive updates and scoped local acceptance commit (no push),
then rotate to a fresh coordinator for R08b bounded download validation. R08c complete canonical baseline/absence and
publication recovery, R09 durable authority/journal, R10 convergence and R12/live activation remain dependent work.
No HTTP orchestration, app construction, refresh replacement, live edit/timer barrier, UI/scheduler, account cleanup,
import/replace, backend source/configuration/suite, personal fixtures/devices, Google action or deployment.
Every unresolved blocker/question goes to the user; never substitute a model/gate or silently expand scope.

## Verified inventory and selected simplification

Android HEAD on pickup: `cd612b2ae24a117296489899f6f94119bb1b2ccd`; shared HEAD
`31c969f261bd3f0fe662e633c0dfffecb3c08393`. Both trees/indexes/untracked inventories were clean; preserve subsequent work.
`GymDatabase` and exported schema are version **10**, identity `12bfa6183e3687bf7745d8cf5a92b5dd`.
Versions1–10 are retained. Unchanged bundled catalogue asset is version1, SHA256
`defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
Assign **10→11** solely to the three staging tables below; never edit the asset or use destructive fallback.

Reuse `SnapshotResultModel`, complete `SyncChangeModel`/canonical aggregate models and existing codecs/validators,
`GymSyncApi.snapshot`, secure HTTP/refresh, canonical ledger/batches/protected publisher and R07 ownership/checkpoints.
No new wire model/codec/API, owner, engine or canonical writer. Owner explicitly authorized the minimal existing
`validateSnapshotResult` continuation correction from nonblank to **nonempty** plus affected independent tests in R08a:
tokens are opaque; a whitespace-only nonempty token is valid and stays exact. Missing/empty tokens still reject.
Repository account identity remains opaque nonblank service identity; aggregate/stage IDs are canonical lowercase UUIDs.

Personal simplification: **discard-and-restart**, not cross-run resume. A stage begun in a live run can chain page
storage in that run. Every durable read (including restart/uncertain write recovery) produces only an abandonable
observation, never resumed append authority. Explicitly abandon that exact stage, then begin a NEW stage UUID with null
request token. Nothing cleans up automatically on cancellation, run close, invalid token or read failure.
A new run may observe/abandon an old same-account stage under its CURRENT authorized session/epoch; it never adopts the
old stage's origin fence for writes. Other-account stages remain untouched. Corrupt retained staging fails closed and is
preserved; future explicit corruption recovery is not silently invented here.

## Concrete durable schema — Room11

All names below are normative. No credential/Google proof/origin secret or visible-domain/canonical FK.

`sync_snapshot_stages` / `SyncSnapshotStageEntity`:

| Kotlin field | SQL column/type | Constraint/default |
|---|---|---|
| stageId | stage_id TEXT | PK, nonnull UUID |
| accountId | account_id TEXT | nonnull, UNIQUE index |
| sessionGeneration | session_generation INTEGER | nonnull positive origin generation |
| cacheEpoch | cache_epoch INTEGER | nonnull nonnegative origin epoch |
| pageLimit | page_limit INTEGER | nonnull, fixed1..500 |
| sourceStatePresent | source_state_present INTEGER | nonnull Boolean |
| sourceCursor | source_cursor TEXT | nullable exact original checkpoint |
| sourceLastSuccessMillis | source_last_success_millis INTEGER | nullable exact original observation |
| sourceBaselineComplete | source_baseline_complete INTEGER | nonnull Boolean (absent cache means false) |
| baselineCursor | baseline_cursor TEXT | nullable until first page; then nonempty exact frozen baseline |
| nextPageToken | next_page_token TEXT | nullable exact pending continuation |
| phase | phase TEXT | nonnull default 'downloading'; downloading/downloaded ONLY |
| pageCount | page_count INTEGER | nonnull default0, checked nonnegative Long |
| recordCount | record_count INTEGER | nonnull default0, checked nonnegative Long |

`sync_snapshot_records` / `SyncSnapshotRecordEntity`:
`stageId/stage_id TEXT`, `aggregateType/aggregate_type TEXT`, `aggregateId/aggregate_id TEXT`,
`serverRevision/server_revision INTEGER`, `canonicalJson/canonical_json TEXT`,
`pageNumber/page_number INTEGER`, `recordPosition/record_position INTEGER`; all nonnull.
PK `(stage_id,aggregate_type,aggregate_id)`; UNIQUE index `(stage_id,page_number,record_position)`.
Stage FK `stage_id→sync_snapshot_stages.stage_id`, ON DELETE CASCADE, ON UPDATE NO ACTION.
Represented bodies are whole canonical upserts, positive revision, page numbers1-based, record positions0-based.
Do not compare/merge/revision-arbitrate staged roots: ANY duplicate root key across pages rejects the whole new page,
even identical records. Independent workout/routine namespaces may overlap. Global references may arrive on later pages.

`sync_snapshot_tokens` / `SyncSnapshotTokenEntity`:
`stageId/stage_id TEXT`, `pageToken/page_token TEXT`, `pageNumber/page_number INTEGER`; all nonnull.
PK `(stage_id,page_token)`; UNIQUE index `(stage_id,page_number)`; same stage-only CASCADE FK.
One exact outbound continuation per successfully committed NONTERMINAL page. This is durable cycle/progress evidence,
not token interpretation. No initial null token row. No arbitrary token/string/domain-size cap.

Use Room-compatible indices covering FK prefixes (PK prefixes suffice; avoid new missing-index warnings).
Migration creates only these tables/indices, leaves EVERY previous value/byte/sequence/schema unchanged and starts them
empty for all v1–10 upgrades. `GymDatabase` registers these entities, `syncSnapshotDao()` and the migration.
`SyncSnapshotDao` provides public read-only `getStageForAccount(accountId)`, `getRecords(stageId)` ordered page/position
and `getTokens(stageId)` ordered page; mutators internal and transaction-only. Record/token entity constructors use the
field names above so QA can inspect durable evidence without production source.

## Concrete storage API — existing PushRun

```kotlin
suspend fun beginSnapshotStage(
    stageId: String,
    pageLimit: Int = DEFAULT_SNAPSHOT_LIMIT,
): SnapshotStage
suspend fun readSnapshotStage(): SnapshotStage?
suspend fun checkSnapshotStage(stage: SnapshotStage)
suspend fun appendSnapshotPage(stage: SnapshotStage, page: SnapshotResultModel): SnapshotStage
suspend fun abandonSnapshotStage(stage: SnapshotStage)
```

New `store/SnapshotStage.kt`: opaque `SnapshotStage` has no public constructor/copy and exposes immutable
`stageId: String`, `pageLimit: Int`, `baselineCursor: String?`, `nextPageToken: String?`, `downloaded: Boolean`,
`pageCount: Long`, `recordCount: Long`, `canAppend: Boolean`. Internally binds originating live run/current admission
and complete observed immutable header. Fixed `toString()` = `SnapshotStage`; never print IDs/tokens/bodies.
New fixed safe exceptions:
- `StaleSnapshotStageException("Stale snapshot stage")`
- `SnapshotStageStorageException("Snapshot stage storage operation failed")` (no raw cause)
- `SnapshotStageActiveException("Snapshot stage requires explicit completion or abandonment")`
- `SnapshotStageIntentException("Snapshot staging requires an empty account outbox")`

Caller programming bounds/UUID fail before storage with existing safe argument/model validation. Malformed incoming
page/progression raises fixed safe `SyncModelException`; retained corruption raises storage exception.
Session/epoch errors retain existing exception types; cancellation propagates unchanged.

### Ownership and staging exclusion

Reuse ONE explicit application-owned `PushRunOwner` per physical DB, the existing `PushRunScope` live capability and
short handling Mutex. Stage operations reject foreign/closed/outside-run/enclosing-transaction use. No separate admission,
owner, public unguarded mutation seam or network inside Room. API/store must eventually share authorized service/account/
session; this inert storage cannot authenticate a typed page. R08b will prove actual HTTP download policy.

A durable selected-account stage, including downloaded/corrupt origin metadata, excludes ordinary push/pull until explicit
completion (future R08c) or abandonment. Add `PushBlockReason.SNAPSHOT_STAGE`; `claimHead` checks stage existence inside
its fenced transaction BEFORE selecting/freezing any head and returns unchanged Blocked. Existing budget limits still
never probe another head. Outcome/check/ack operations reject stage presence via `SnapshotStageActiveException` inside
their fenced transactions. All incremental checkpoint read/check/batch/tail operations likewise reject selected stage
presence before effects. Foreign stages do not block. Reuse existing drivers; no HTTP/engine changes needed.
Begin also retires any previously admitted pull checkpoint before its first staging write. Presence checks never parse
outbox or stage records, filter around corruption, interpret tokens or create cleanup authority.

### Begin, observation, append and abandonment

Every mutation is ONE transaction with pre/post live admission, `checkCanonicalFences` and cancellation checks, exact
header/update-count checks and owned SQLite sanitization. Earlier committed pages survive later failure. A failed or
uncertain write does NOT prove rollback: reread durable evidence, explicitly abandon and restart; retired tokens never
prove a commit. Keep one current stage token reference under handling Mutex, not an unbounded capability ledger.

**Begin**: require no selected stage and selected account outbox `EXISTS=false` (ANY state/error/version counts).
Validate and capture original full sync-state row including presence/null cursor/null last-success; reject corrupt
empty cursor/negative last-success without repair. Capture source baseline-complete (absent cache=false), session/epoch
and fixed limit. Insert initial stage only; no cache row creation. StageID collisions fail closed without overwriting
foreign rows. Repeat source-state/baseline and empty-outbox/fence checks after insertion. Return appendable token after
returned commit and active resumption. A retained/corrupt/rejected/frozen/deferred head cannot be bypassed with staging.

**Read**: supersede prior stage token even if null/failure. Coherently read selected stage and validate its complete
retained metadata/records/progress evidence; no mutation. Return an abandonable (`canAppend=false`) current-run observation
or null. Original generation/epoch/source may differ from current; that means recovery observation, NEVER append authority.

**Check**: require current exact token/header and coherent valid retained evidence under current live fences. For an
appendable token also require origin generation/epoch and source complete sync-state/baseline binding still exact.
Recovery observations ignore OLD source fences solely to permit explicit same-account abandonment under CURRENT fences.

**Append**: synchronously DEEP-own and validate the ENTIRE typed page before first suspension, using existing snapshot/
record codecs. Reject delete/bundled/invalid aggregates, within-page duplicates, count above fixed pageLimit,
empty nonterminal pages, missing/invalid continuation and terminal nonnull continuation. Preserve all represented fields,
IDs/nulls/ordered children/optional metadata/quantity/names/times/rest, without splitting/truncating/merging.
Reuse the existing validator with the owner-authorized nonempty continuation correction above; preserve whitespace
exactly rather than trimming/interpreting tokens. Do not change any other codec/model behavior.
Require appendable current token, downloading phase, exact retained full header, origin generation/epoch, unchanged
source full sync-state and source baseline-complete. First page binds baseline; later pages match it EXACTLY.
`nextPageToken` in input authority is the requested token for this page (null initially); new nonterminal output must
be new versus ALL retained exact tokens (including current request), never interpreted or ordered. Insert records/token
and advance header/counters atomically. Consecutive page/record positions and checked Long counters. Duplicate cross-page
root or continuation rejects before writes, no replace/ignore. Local capture DURING download is allowed: do NOT require
outbox empty after begin; domain/outbox/newer intent/watermarks are never changed by staging. Retire token immediately
before first write, never restore on rollback/uncertain commit. Revalidate exact postheader, retained evidence, source,
fences/cancellation before commit; only returned commit/active resumption installs fresh token. Terminal phase is
`downloaded`, continuation null, `canAppend=false`; it still cannot establish baseline/absence/publication/success.

**Abandon**: explicit exact current token/header, selected account and CURRENT session/epoch checks; it may be an old
origin recovery observation. Validate retained evidence before deleting; retire token immediately before write. Delete
ONLY exact stage with count1 and stage-only child cascade; prove header/records/tokens absent, recheck current fences/
cancellation. No source-state check against OLD origin, no outbox requirement, and no domain/cache/checkpoint changes.
Never blanket delete all stages or use invalid/foreign read tokens to infer abandonment. Cancellation/run close retains
stage unless explicit transaction durably committed; no finally cleanup or silent stage replacement.

### Retained evidence validity

Validate header UUID/nonblank account/positive origin generation/nonnegative epoch/fixed1..500 limit/nonnegative counters,
known phase and original source row shape (presence=false implies both source nullable fields null).
Initial stage: pageCount0/recordCount0, baseline/continuation null, downloading, zero child rows.
After pages: baseline nonempty; downloading requires continuation equal token for pageCount; downloaded requires null
continuation and pageCount>0. Token pages consecutive1..pageCount for downloading,1..pageCount-1 for downloaded.
Every nonterminal page has1..limit records with consecutive positions; terminal has0..limit. Records' pages/positions,
actual counts and header match; no extraneous pages/rows. Decode stored canonical JSON through strict retained-record
reader, and require type/ID/revision agreement and whole aggregate validation. No repair/normalization of retained bytes.
This is per-record/progress validity, NOT whole-account dependency/sole-active/physical-collision validation; that belongs
to complete R08 validation/publication. Preserve stages with later-page references or intermediate multiple active roots.
No permanent history/record/token cap; work can grow with account size. Optimization must not weaken validity.

## Independent roles / disjoint allowlists

Fresh native Pi **meta/muse-spark-1.3-contributor/high** per role, not R07c sessions. Both read this normative spec;
apply the [progressive reading policy](../../README.md#progressive-reading-and-context-discipline) and reuse supplied context.
Task references are [snapshot design §5](../../sync/integration-design.md#5-frozen-snapshots-and-full-resync),
[testing risk policy](../../testing-strategy.md#proportional-coverage-and-verification-gates), and the shared
[frozen snapshots](../../../shared-docs/docs/backend-contract/service-protocol.md#frozen-sync-snapshots-and-lightweight-list-reads-t2t8),
[sync paging bounds](../../../shared-docs/docs/backend-contract/client-compatibility.md#paging-and-recovery) and
[snapshot consistency](../../../shared-docs/docs/backend-contract/android-client-protocol.md#snapshot-consistency).
Expand only for concrete dependency/safety questions; no blanket whole-contract/OpenAPI/system reading. All source boundaries include LSP/diffs/generated classes/decompilation/reports. Reports fire-and-forget
`herdr agent prompt gym-coordinator ...`; no `--wait`, agent wait or acknowledgment polling. No contributor Gradle slot.
Coordinator owns both-layer review, shared harness/build/docs integration and all explicitly serialized Gradle runs.

**Worker read** only production `data/gym/`, `data/sync/`, exported schemas and docs. No tests/fixtures/reports/QA sources.
**Worker write ONLY** (paths under `app/src/main/java/com/dragosfotache/gymtracka/data/`):
- existing `gym/GymDatabase.kt`; new `gym/SyncSnapshotEntities.kt`, `gym/SyncSnapshotDao.kt`, `gym/SnapshotStorageMigration.kt`
- existing `sync/store/PushStore.kt`, `PushClaim.kt`; new `SnapshotStage.kt`, `SnapshotStageStorage.kt`
- existing `sync/model/ProtocolValidation.kt` ONLY the authorized continuation `isNullOrBlank`→`isNullOrEmpty` correction
- no schema manual edits (coordinator exports KSP), other domain/HTTP/engine/session/model/codec changes or new owner.

**QA read** only client `app/src/androidTest/`, offline `app/src/test/` excluding backend, schemas1–10 and docs/spec.
No production declarations/implementation/diffs/generated classes/Worker work. Missing facts go to coordinator.
**QA write ONLY**:
- new `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/SnapshotStageTest.kt`
- new `.../SnapshotStageFenceTest.kt`, `.../SnapshotStageTestSupport.kt`
- existing `app/src/androidTest/java/com/dragosfotache/gymtracka/data/gym/GymDatabaseMigrationTest.kt`
- new `.../data/gym/SnapshotStorageMigrationTest.kt`
- existing offline `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ProtocolEnvelopeTest.kt` ONLY focused
  nonempty opaque snapshot-token compatibility checks
- existing offline `.../data/sync/PushClaimContractTest.kt` ONLY add the specified `SNAPSHOT_STAGE` reason to the
  independent exact contract-set expectation (existing assertion remains exact)

### Source-independent harness facts

`GymDatabaseTest.database` is real in-memory Room; it closes after each case. Existing `claimSession(account,generation)`,
`claimOwner()`, `database.claimPushStore(session,owner)`, `claimSeedDevice`, `claimSeedCache`, `claimUpdateCache` and
`claimExec`/`claimSnapshot`/`claimCount` work. `claimSnapshot` observes all SQLite app tables; for allowed staging changes
compare unchanged prior-table contents using test-only snapshots excluding the3 staging tables, not assertion weakening.
`pullSeedBaseline` clears ALL cache rows: seed other scope AFTER it. `claimFileDatabase(context,name)` supports reopen.
Capture helpers provide exact frozen/pending rows; compare stored bytes before/after, never generated expected JSON.
`pullWorkoutRecord`, `pullRoutineRecord`, `pullCustomRecord`, `pullUpsert`, hand-authored wire helpers and typed
`SnapshotResultModel(records,baselineCursor,nextPageToken,hasMore)` support fixtures; no production encoder as oracle.
`SyncSnapshotDao.getStageForAccount(accountId)`, `getRecords(stageId)`, `getTokens(stageId)` are read-only entity lists.
Stages begin via admitted `PushStore.withRun(epoch) { run -> ... }`; returned closed-run tokens cannot be reused.
`RepositorySession` accepts opaque account-a/account-b; positive generation7 is established harness default.

Migration helper uses `GymDatabase.MIGRATIONS`, all-supported starts currently1..9→10, and copied actual v1 asset.
Update target11/start1..10; preserve existing explicit populated9→10 historical regression. New populated10→11 case must
seed all prior tables INCLUDING canonical upsert/delete/unobserved/snapshot_absent, nonzero watermarks/cache/epochs,
opaque checkpoint/null last-success, pending/frozen/rejected/corrupt/unsupported outbox and A/B/local/catalogue; compare
complete existing table bytes/rows, sequence allocation and FK/index schema. Verify new tables empty.
No mock DAOs/storage or SQLite user/personal databases. Controlled transaction executor admission + separate query executor
proves pre-suspension deep ownership. Query callbacks fire BEFORE SQL: post-write cancellation requires observing a write
then cancelling on FOLLOWING query, asserting hook fired/full rollback. Explicit inherited `coroutineScope` inside live
run preserves context in children; do not accidentally use runTest receiver async. Trigger faults/RAISE(IGNORE) prove
write count and postheader/source/fence checks. Source-independent expected results are literal full records/rows.

## Risk-selected acceptance obligations and REQUIRED gates

Risk: schema plus durable sync ownership/cancellation foundation, inert/no wire or activation. Distinct new obligations:
1. Initial and empty terminal stages; >one page/all3 families including large whole record and later-page dependency;
   baseline/limit/whole-record fidelity, correct page/token/root evidence; terminal remains outside canonical/domain.
2. Whole-page atomicity: representative malformed/count/duplicate roots across pages/baseline mismatch/token cycle,
   no-progress and terminal shape; earlier committed pages preserved, no partial row or header update. Focused independent
   JVM codec and Room-stage checks cover exact whitespace-only continuation, with missing/empty still rejected.
3. Begin rejects ANY selected-account outbox state, existing stage and foreign stageID collision; foreign-account intent/
   stage isolation. Local capture afterbegin preserved. Selected stage blocks push/pull through existing drivers, budget
   still no probe, abandonment restores eligible existing behavior without request/checkpoint/success rewriting.
4. Full header/source/session/epoch/current-token fencing; foreign/closed/superseded/recovery token cannot append;
   trigger rollback/update-count/queued and post-write cancellation, concurrent same-token settlement once, owner held
   across caller gates. Uncertain attempt requires durable reread, explicit abandon/restart, not token inference.
5. File close/reopen with partial and downloaded stage: no implicit resume/publication/cleanup; explicit current-account
   abandon preserves original/newer outbox/domain/cache/other scope/catalogue. Corrupt retained JSON/progress preserved
   as safe failure. Unknown token failure/expiry orchestration is NOT implemented here.
6. Migration EVERY supported1–10 start→11, populated10 exact preservation and actual copied bundledv1 asset; new
   tables empty, old schemas unchanged, KSP export current11. Reuse old codec/canonical/driver matrices rather than
   duplicate their entire field validation. No arbitrary test-count quota.

Iteration: serialized production/KSP/Android-test compile, new focused Room/migration classes separately (not comma filters).
Acceptance: **fresh production/KSP and Android-test compilation**, **fresh full offline JVM** with task-scoped `--rerun`,
**full unfiltered connectedDebugAndroidTest --rerun** on isolated test-owned emulator (schema/exclusion risk brings full
relevant regression forward), **explicit assembleDebug lintDebug** and lint XML/no new findings plus APK checks for
unchanged packaged catalogue/notices and absent project test/backend/JDBC/credential/config artifacts. Existing FK-index
KSP warnings and test-only org.json newer-version lint are baseline, not permission to hide new warnings. Inspect actual
nonempty executed XML names/counts/failures/errors/skips and shell exits; filtered/cached/compilation is not fresh full pass.
No new backend gate: no wire/HTTP/refresh change. R08b/c package gates and R10 two-store HTTP convergence remain separate;
API24/hostedCI/Google/personal-device/public milestones are not claimed here. Required unavailable emulator/tool/model/gate
is a user blocker, not substituted/deferred. Test-owned resources may be provisioned and cleaned, unrelated ADB/Gradle
and personal configurations preserved. Every Gradle command has an explicit sole coordinator slot.

Check both repositories' complete diffs/index/untracked state, source asset/schema1–10 hashes, documentation links and
`git diff --check` before acceptance. Preserve strict legacy accepted-auth replay limitation unchanged. Update only owning
Android docs for delivered boundary, retain shared contract unchanged, archive this specification/review, commit scoped
slice locally, then asynchronous exact-model fresh-coordinator readiness/role transfer and self-pane close LAST.
