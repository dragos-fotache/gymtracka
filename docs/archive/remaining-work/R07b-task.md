# R07b — Bounded incremental HTTP pull

> Archived accepted specification. Current implementation is in [sync implementation](../../sync/implementation.md);
> this historical record grants no new authorization.

## Authority, goal and stop

The owner released the fresh coordinator to R07b under the [standing workflow](../../coordinator-workflow.md).
Deliver an inert explicitly constructed incremental pull driver on the existing admitted `PushRun`; independently
review and execute the declared gates, update owning docs, archive this specification/review and commit only accepted
slice changes locally. Then rotate to a fresh coordinator for dependency-ready R07c. No push or live action is authorized.

Non-goals: R07c shared push-before-pull policy/orchestration or tail/last-success integration; R08 bootstrap/snapshot;
R09 durable authority/transitions; R12 real edit/timer barriers; publication, outbox rewriting, production construction,
UI, scheduler, schema/entities/migration/assets/dependencies/configuration, backend source or unrelated cleanup.
A valid incremental tail is not account convergence. The production app stays signed-out/local-only.

## Inventory and dependencies

Accepted R07a follows R06/R05/R04. Room remains v10. The [implemented seams](../../sync/implementation.md) and
[design §§2–4](../../sync/integration-design.md) apply; finalized shared
[contract](../../../shared-docs/docs/backend-contract/README.md), especially
[pull boundaries](../../../shared-docs/docs/backend-contract/service-protocol.md#pull-boundaries-and-retention-t2), is unchanged.

- `PushStore(database, session, owner).withRun(expectedCacheEpoch, block)` admits one live `PushRun`. One required
  `PushRunOwner` is shared by every store/caller for one physical DB, including network intervals. Nested admission
  rejects. A run capability is context-bound, closes after structured children settle, and is not durable Room/vault authority.
- `PushRun.readPullCheckpoint(): PullCheckpoint?` coherently requires complete baseline and nonnull durable cursor;
  null requires R08, never null-cursor history bootstrap. Corrupt retained state fails closed. A new read attempt retires
  earlier tokens. `PullCheckpoint.cursor: String` is public; constructor/bindings/copy are not public.
- `checkPullCheckpoint(checkpoint)` rechecks complete sync-state row, baseline/session/epoch/live capability in Room,
  read-only. `applyNextPullBatch(checkpoint, SyncBatchModel): PullBatchCommitResult` owns the batch before suspension,
  atomically commits complete canonical application plus only its cursor, and returns `.checkpoint` for chaining.
  It retires the input token before writes even after rollback/uncertain commit. Failed/uncertain commit requires durable reread.
- R07a preserves domain/projection/outbox/watermarks/baseline/epochs/last-success/other scopes. Intermediate references
  may be unresolved; only explicit DELETE is deletion. Existing canonical revision/family arbitration is reused.
- `GymSyncApi.pull(cursor: String?, limit: Int = 100): PullResultModel` is the transport boundary. Page limits are
  1..200 complete batches. The HTTP adapter already strictly decodes whole pages and validates page-local invariants,
  count/progress; the driver must validate typed/scripted responses too and track cross-page cycles. HTTP is outside Room.
- `SessionRefreshingGymSyncApi` owns the existing single unchanged 401 retry/refresh boundary; driver adds no refresh,
  retries/delay, token parsing, credentials or scope. API pairing with the same authorized service/account/session as
  the admitted store remains an explicit caller obligation, not something inferred from an untagged interface.
- `PullResultModel(batches: List<SyncBatchModel>, nextCursor: String, hasMore: Boolean)` and
  `SyncBatchModel(batchId: String, cursor: String, changes: List<SyncChangeModel>)` have caller-mutable nested lists.
  `decodePullResult(encodePullResult(page))` validates/deep-owns all represented records; production may reuse this
  existing codec round trip (no independent test oracle may be generated with an encoder).
- Existing `validatePullResult` checks complete batches, nonempty cursor, unique page batch IDs/cursors,
  nonempty nonterminal page and last-batch/nextCursor equality. It does not check input boundary, count or run cycles.

## Concrete API

New files only under production `data/sync/engine/`:

```kotlin
const val MAX_PULL_DELIVERY_PAGES = 100

class PullDeliveryLoop(private val api: GymSyncApi) {
    suspend fun pull(
        run: PushRun,
        maxPages: Int = MAX_PULL_DELIVERY_PAGES,
        pageLimit: Int = DEFAULT_PULL_LIMIT,
    ): PullDeliveryResult
    override fun toString(): String // fixed "PullDeliveryLoop"
}

data class PullDeliveryResult(
    val committedBatches: Int,
    val stop: PullDeliveryStop,
) // override toString: counts + safe stop only, never IDs/tokens/bodies

sealed class PullDeliveryStop {
    data object Tail : PullDeliveryStop()
    data object LimitReached : PullDeliveryStop()
    data class RecoveryRequired(val reason: PullRecoveryReason) : PullDeliveryStop()
    class Failed(val failure: SyncApiException) : PullDeliveryStop()
    // Every string form is fixed/redacted; Failed never prints the supplied exception or service code.
}

enum class PullRecoveryReason { MISSING_BASELINE_OR_CURSOR, CURSOR_EXPIRED }
```

The driver receives an **already admitted run**, not a store/epoch. It owns no admission/mutex/owner, so eventual R07c
can invoke it uninterrupted after push on the same run without nested entry. Typical explicit use:
`store.withRun(epoch) { run -> PullDeliveryLoop(pairedApi).pull(run) }`.
No standalone store-admitting overload or push refactor is needed. No API for deferred package policies is introduced.

`maxPages` must be strictly 1..100 and `pageLimit` strictly 1..200; reject invalid parameters with fixed
`IllegalArgumentException` before checkpoint or API work (do not clamp silently). Budgets count HTTP pages, not bytes,
aggregates, changes or revisions. No content-size cap/truncation is introduced. At most 20,000 complete batches are
fetched; seen-ID/token sets are bounded by these budgets, not durable account-lifetime history.

## Algorithm, safety and failure contract

1. Validate parameters and caller cancellation. Read exactly one fresh checkpoint. Null returns zero commits and
   `RecoveryRequired(MISSING_BASELINE_OR_CURSOR)` with no HTTP (especially no null cursor/snapshot). Corrupt storage,
   session/epoch/admission failures propagate, never become bootstrap permission.
2. Initialize run-local seen cursors with the exact initial cursor, and seen batch IDs empty. Preserve strings exactly,
   including whitespace. No parsing, age inference, lexical/numeric sort, normalization or authentication claim.
3. Before **each** call recheck checkpoint and cancellation immediately before `api.pull(checkpoint.cursor, pageLimit)`.
   Fetch outside Room under the caller's uninterrupted owner. Use the fresh returned checkpoint after each commit,
   never reread between successful sequential commits or derive a boundary from a push acknowledgment.
4. Catch only `SyncApiException` from the actual API invocation. Recheck caller cancellation and the same checkpoint
   **after** an API success or typed failure, before classification/application. This also fences empty-tail and failure
   results after session/epoch/row/baseline changes. A pre-send check cannot recall admitted bytes.
5. On a typed API failure, explicit `CursorExpired` returns `RecoveryRequired(CURSOR_EXPIRED)`. All other categories,
   including invalid/foreign cursor, malformed response, wrong-operation snapshot failures, auth/reauth, transient,
   oversized and unknown permanent rejection, return `Failed` carrying that exact failure. No write/retry/cleanup or
   implicit bootstrap. Unexpected API exceptions preserve provenance and propagate. Cancellation always propagates.
6. Immediately after API return and caller cancellation check, deep-own and validate **the entire page synchronously
   before the post-response checkpoint check or any other suspension/page commit**. The post-response check still
   precedes returning any malformed/tail result or applying any owned batch. Invalid typed shape (existing model/JSON
   validation), excess batches, cycles or boundary
   inconsistency returns `Failed(SyncApiException.MalformedResponse())` with no commits from that page. Do not convert
   later Room/canonical/admission errors into transport failures. Codec failure wrapping is confined to page ownership.
7. Validate count <= requested limit; nonempty `nextCursor`; complete ordered nonempty batches; page-local unique IDs
   and cursors; `hasMore` cannot be true for empty page; nonempty page `nextCursor` equals last batch cursor. Every batch
   ID must be new across the invocation; every batch cursor must be new versus initial boundary and all earlier/current
   page batches. Validate the whole page's run progression **before** committing its first batch. `nextCursor` is the
   same last-batch boundary, not an additional distinct token. Opaque tokens cannot prove service-internal ordering;
   contiguity is enforced by using the exact admitted input and committing returned order with each batch's own boundary.
8. Empty terminal page must preserve the exact input cursor. Read-only post-response checkpoint check fences it;
   return `Tail` without writes. Absence never deletes anything. A terminal nonempty page is valid even at the budget.
9. Commit owned batches in returned order via `applyNextPullBatch`, replacing current checkpoint only with its returned
   fresh token; increment `committedBatches` only after a returned successful commit. Any storage/canonical/fence failure
   or cancellation propagates and stops: earlier committed batches remain; failed batch and remaining page are not
   advertised as committed. No in-run retry/reread after uncertain commit. A new invocation/restart rereads durable state.
10. After a validated nonempty page fully commits, `hasMore=false` returns `Tail`; otherwise if `maxPages` calls have
    been used return `LimitReached` without another transport/probe. Otherwise fetch from current durable chain boundary.
    Check cancellation before returning summaries. Tail/LimitReached/recovery/failure update no last-success/convergence.

Caller structured cancellation releases shared owner only after children settle. A late ignored-cancellation response
cannot write or report recovery/tail through stale fences. New local capture during HTTP remains intact; the primitive
may store canonical observations while intent exists but cannot publish. R07c, not this driver, will enforce rejection
and push-first policy. No shared ownership is released/reacquired by this driver.

## Independent ownership

Both roles read this spec, AGENTS/docs README/workflow/testing/architecture/sync implementation/design and relevant shared
product/contracts. Use bounded mcpls only within allowed sources. Coordinator alone owns design/docs/integration/final
review and builds unless granting an explicit serialized slot. Initially neither role has a Gradle slot.
Use fresh native Pi `meta/muse-spark-1.3-contributor` high sessions, asynchronous reports via
`herdr agent prompt gym-coordinator "..."`, no `--wait`, agent wait or acknowledgment polling.

**Worker** may read production `data/sync/` and `data/gym/` as needed; must not inspect tests/fixtures/reports/QA output,
compiled tests or QA diffs. Write only new:
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/engine/PullDeliveryLoop.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/engine/PullDeliveryResult.kt`
Optional private helpers belong in those files. No existing production changes without coordinator review/permission.

**QA** may read existing client `app/src/androidTest/`, `app/src/test/` (exclude backend), exported v10 schema and the
above documentation. Never production declarations/implementation/generated classes/diffs, decompilation or Worker work.
Write only new:
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PullDeliveryTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PullDeliveryFenceTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PullDeliveryTestSupport.kt`
Use real Room, no DAO/PushRun mocks or duplicate engine implementation. Ask coordinator for missing facts.

### Source-independent QA harness facts

- Existing `GymDatabaseTest` owns an in-memory `database` and closes it after tests. `claimPushStore(session, owner)` /
  `claimSession` / `claimOwner` are existing helpers; pass one explicit shared owner for multiple stores/callers.
- `pullSeedBaseline(cursor, lastSuccessMillis, account, cacheEpoch, baselineComplete)` seeds device/cache/state;
  it deletes cache rows, so add other scopes only afterward. `pullSyncCursor`, `pullLastSuccess`, `claimSnapshot`
  and direct DAO/SQL observations already exist. `claimSnapshot` covers every application table.
- `pullBatch`, `pullDelete`, `pullUpsert`, `pullWorkoutRecord`, `pullRoutineRecord`, `pullCustomRecord` and hand-authored
  raw fixtures already exist. Use different literal batch IDs/cursors per actual batch; no expected bytes from encoders.
  `pullBlockCursorWrites` / `pullDropTrigger` support atomic rollback; existing transaction-executor/query-hook
  techniques cover owned-input/cancellation faults. Reuse rather than repeat all canonical/codec/family matrices.
- `GymSyncApi` scripted implementations override push/pull/snapshot; push and snapshot should fail if called. Script
  `PullResultModel` directly, including malformed typed pages; assert HTTP is outside `database.inTransaction()`.
  Existing sync exceptions have no-arg constructors except `Transient(retryAfterMillis: Long? = null)` and
  `RequestRejected(errorCode: String)`. Failure identity and code can be asserted through `Failed.failure`.
- Fence/storage exceptions: `StaleSessionException`, `StaleCanonicalEpochException`, `StalePullCheckpointException`,
  `PullStorageException`, `CanonicalConflictException`, `CanonicalStorageException`. `PullDeliveryLoop` has no store
  constructor; invoke inside `withRun(0L)`. File-reopen helpers are in `PushClaimTestSupport`; no schema change.
- The returned summary does not expose a checkpoint. A subsequent same-run caller may explicitly reread; old read/write
  tokens remain retired according to R07a. QA should assert durable cursor rather than infer private token state.

## Risk-selected tests and gates

Risk: sync pagination/canonical commit sequencing, with no schema/activation/security packaging changes.
Distinct new obligations (representative cases, not a count quota):

1. Multiple pages/batches, complete active replacement and later dependencies, exact opaque input/limit order and fresh
   token chaining; allowed canonical/cursor effects only, last-success/domain/newer intent/other scopes preserved.
2. Empty and nonempty tail; page-budget exhaustion without extra call; strict bounds/default limits; missing baseline/
   cursor returns R08 need with no transport; corrupt state remains an error.
3. Whole-page malformed rejection before first commit: count/hasMore/nextCursor/progress and initial/page/cross-page
   cursor/batch-ID cycles. Reuse decoder matrix; table-driven distinct boundary cases are appropriate.
4. Explicit expiry versus invalid/foreign/wrong-context/transient/auth/unknown failure classification, no snapshot/
   null-cursor/retry/reset. Earlier commits survive later page transport or malformed-page failure.
5. Earlier same-page commit survives later canonical/SQLite commit failure; durable reread/reopen resumes exact boundary
   with no skipped page tail and no uncertain-token reuse. Reuse R07a rollback tests for unchanged store internals.
6. Session/account/epoch/full-row/baseline changes during transport fence data **and empty/failure results**; cancellation
   in queued ownership/HTTP/commit stops without later fetch, earlier commits durable; owned whole page cannot mutate
   while a first commit suspends. Deterministic gates, no sleeps.
7. Shared owner serializes two calls/stores including network; caller can use an existing admitted run before/after the
   driver without nested admission, and no driver-owned mutex/owner is introduced. Fixed redacted displays.

Iteration: coordinator-owned `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`, then separately filtered new
Room classes as available. Acceptance: both compilations/KSP; fresh full offline JVM `:app:testDebugUnitTest --rerun`;
separately filtered real Room runs of both new classes plus existing `PullCheckpointTest` and `PullCheckpointFenceTest`.
Use explicit serialized Gradle slots; inspect actual nonempty XML testcase names/counts/failures/errors/skips.
Unavailable required Room gate blocks acceptance. An isolated test-owned emulator may be provisioned; no personal device.

Full unfiltered instrumentation, explicit assembly/lint/APK gates remain **R07c package gates** unless unexpected changes
bring them forward. Existing HTTP/codecs/refresh are reused without new wire behavior; no new backend gate is required
for this bounded driver. Real-service Room-engine convergence remains R10. No cached/full/API24/live/activation claim.
Review both full diffs/trees, asset/schema preservation, doc links and `git diff --check` in both repositories. Record
actual commands/results/residual obligations in one review, archive and commit the accepted slice, then rotate. Stop
implementation before R07c: its new coordinator must specify shared orchestration and tail/last-success policy.
