# R07c — Uninterrupted push-before-pull and valid-tail bookkeeping

> Archived accepted specification. Current implementation is in [sync implementation](../../sync/implementation.md);
> this historical record grants no new authorization.

## Authority, goal and stop

Owner explicitly released the fresh coordinator after accepted R07b under the
[standing workflow](../../coordinator-workflow.md). Deliver one inert bounded shared driver, minimal compatible admitted-run
push composition, and transactional valid-tail/last-success bookkeeping. Independent Worker/QA implement the disjoint
layers below; coordinator owns design/integration/final review and every Gradle slot. Required R07 package gates are
**full unfiltered instrumentation plus explicit debug assembly/lint/APK checks**, not optional/deferred.

Stop after reviewed gates, owning-doc updates, archived spec/review and scoped local acceptance commit (no push), then
rotate to a fresh coordinator for dependency-ready bounded R08. Every unresolved blocker/question goes to the user.
No production construction/activation, UI/scheduler, bootstrap/snapshot/R08, durable authority/account transitions/R09,
convergence/R10, live edit/timer barriers/R12, schema/entities/migration/assets/dependencies/configuration, backend source,
private fixtures/configuration, deployment or live Google/account/personal-device actions.

## Settled inventory and composition decisions

Room v10 and accepted R04–R07b remain the foundations. Read [implementation](../../sync/implementation.md),
[design §§2–4](../../sync/integration-design.md) and full linked [shared contract](../../../shared-docs/docs/backend-contract/README.md).

- `PushStore(database, session, owner).withRun(epoch, block)` admits one context-bound live `PushRun`. One explicit
  application-owned `PushRunOwner` MUST be shared by every store/caller for each physical DB. Structured children settle
  before release; nesting/enclosing Room entry rejects. This is not multiprocess/durable vault authorization.
- Existing `PushDeliveryLoop(store, api, nowMillis).deliver(expectedCacheEpoch, maxMutations=100)` currently owns admission.
  Preserve that signature/constructor and behavior; add the overload below, moving the existing head loop into it.
  Standalone validation stays before admission; admitted overload validates budget and never admits/releases another run.
  No replacement store abstraction, new owner, copied push algorithm or release/reacquire gap.
- `PullDeliveryLoop(api).pull(run,maxPages=100,pageLimit=100)` already receives the admitted run. Preserve its public
  signature/results and standalone no-last-success semantics. Add ONLY the internal terminal callback seam below;
  it shares the original implementation, whole-page ownership, validation, cycle tracking and commit chain.
- `readPullCheckpoint(): PullCheckpoint?`, `checkPullCheckpoint(checkpoint)` and
  `applyNextPullBatch(checkpoint,batch): PullBatchCommitResult` reuse the run handling Mutex and full-row/baseline/
  account/session/epoch/cancellation fences. Another read attempt supersedes older tokens; any write attempt retires
  its input token even on rollback/uncertain commit. Successful writes return fresh tokens. Token retirement is not
  persistence evidence. Failed/uncertain work propagates and requires a durable reread in later explicit recovery.
- Complete canonical/ack arbitration, exact frozen identity/retry, API errors and supplied refresh decoration stay intact.
  API/store MUST be paired to the same authorized service/account/session; an untagged API cannot prove that pairing.
  Push cursors never authorize pull checkpoint or last-success advancement.
- Existing inert `ProjectionStore` is NOT invoked. Eventual publication increments account epoch; future callers must
  continue with its returned epoch. Outstanding newer local intent and projection/convergence are separate from tail.

## Concrete API contracts

Production paths are under `app/src/main/java/com/dragosfotache/gymtracka/data/`.

```kotlin
// Existing engine/PushDeliveryLoop.kt, in addition to the unchanged epoch overload:
suspend fun deliver(
    run: PushRun,
    maxMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
): PushDeliveryResult

// Existing engine/PullDeliveryLoop.kt, internal seam sharing the same private core:
internal suspend fun pullWithTail(
    run: PushRun,
    maxPages: Int,
    pageLimit: Int,
    onValidTail: suspend (PullCheckpoint) -> Unit,
): PullDeliveryResult

// Existing store/PushStore.kt, method on PushRun (NOT PushStore):
suspend fun recordPullTail(
    checkpoint: PullCheckpoint,
    nowMillis: Long,
): PullTailCommitResult

// Existing store/PullCheckpoint.kt:
data class PullTailCommitResult(
    val checkpoint: PullCheckpoint,
    val hasOutstandingLocalWork: Boolean,
) // fixed/redacted toString; no cursor/account/body

// New engine/SyncDeliveryLoop.kt:
class SyncDeliveryLoop(
    private val store: PushStore,
    private val api: GymSyncApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun deliver(
        expectedCacheEpoch: Long,
        maxPushMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
        maxPullPages: Int = MAX_PULL_DELIVERY_PAGES,
        pullPageLimit: Int = DEFAULT_PULL_LIMIT,
    ): SyncDeliveryResult
    override fun toString(): String // fixed "SyncDeliveryLoop"
}

// New engine/SyncDeliveryResult.kt:
data class SyncDeliveryResult(
    val push: PushDeliveryResult,
    val pull: PullDeliveryResult?,
    val hasOutstandingLocalWork: Boolean?,
) // safe count/stop/nullable flag only; no identities/tokens/bodies/service codes
```

`pull == null` means push stopped without authorizing pull. `hasOutstandingLocalWork == null` means no returned successful
valid-tail bookkeeping commit, NOT false/clean. Nonnull is an account-outbox presence observation in that tail transaction;
false is not convergence, projection publication or absence of later edits. No last-success clock is read unless a valid
terminal page reaches the callback. Standalone push reads the clock at its established claim/failure seams unchanged.

The internal callback fires exactly once at a validated empty or nonempty terminal page, with the exact current chained
checkpoint that the driver fenced/committed, **before returning Tail**. It never fires for budget/recovery/failure/errors.
There is no post-Tail reread that could substitute another boundary. Public `pull` calls the same core with a no-op
callback, preserving R07b behavior. Callback storage/fence/cancellation/unexpected failures propagate unchanged, not as
API failures or success. This callback is an internal composition contract, not a new transport policy or reusable
unbounded program. `recordPullTail` is a transaction primitive: its caller is responsible for proving validated terminal
transport exhaustion; generic checkpoints and constructible Tail summaries alone are not transport-tail evidence.

## Shared driver policy

1. Reject negative epoch or budgets outside push 1..100, pull pages 1..100 and page limit 1..200 with fixed
   `IllegalArgumentException` BEFORE admission/clock/HTTP/Room effects. Do not clamp. Check caller cancellation.
2. Admit exactly one `store.withRun(expectedCacheEpoch)` spanning push, all pull HTTP/commits and tail bookkeeping.
   Invoke the admitted push overload, NOT the epoch overload. No extra mutex/owner or nested admission.
3. Only returned `PushDeliveryStop.Empty` permits pull. It means an actual fenced empty explicit-head selection, not
   convergence. Every other stop (LimitReached, deferred, rejection/corruption/unsupported/retained/oversize/exhaustion,
   auth/reauth) returns push-only summary without checkpoint/pull/last-success work. Exceptions/cancellation propagate.
   LimitReached never probes another head even when the last acknowledged row emptied the queue.
4. After Empty, invoke internal pull-with-tail on the SAME admitted run. Initial missing baseline/cursor or explicit
   expiry returns the existing R08 requirement, never snapshot or null-cursor history bootstrap. Invalid/foreign/
   wrong-context tokens retain failure; no cleanup, retry, reset, token interpretation or outbox rewriting.
5. In the validated terminal callback read `nowMillis()` once and call `recordPullTail` on its supplied checkpoint.
   Propagate failures; only a returned transaction result sets the summary's nonnull outstanding-work flag.
6. Return after cancellation check. Earlier returned acknowledgments/batch commits remain durable after any later failure.
   The driver never retries within this run or infers rollback from cancellation after a synchronous commit.

New local capture is allowed while network runs. Empty proves only that earlier boundary; newly captured work during pull
is not silently pushed/rebased/dropped. It remains protected and is reported independently at tail. Do not reenter push or
probe a successor merely to manufacture convergence. The retained-error policy is enforced by finishing the explicit
push phase; legitimate newer capture after that empty boundary remains separate intent, not permission to bypass a
retained predecessor encountered during push. No snapshot/publication/drain is introduced here.

## Tail transaction: exact writes, fences and recovery

`recordPullTail` validates nonnegative `nowMillis` before any write; preserve the supplied wall-clock milliseconds exactly
(no inferred ordering/clamping/max with prior time). Time is observation metadata, never a conflict/cursor clock.

- Reject foreign/closed/outside-run/enclosing transaction, superseded/consumed token exactly like R07a. Use the existing
  handling Mutex (shared with checkpoint/ack operations), not another owner. All checks and writes share one Room transaction.
- Inside Room recheck live admission/cancellation, `checkCanonicalFences`, complete selected-account baseline and EXACT
  observed full `SyncStateEntity(accountId,cursor,lastSuccessMillis)` (including null). Missing/changed row or baseline is
  `StalePullCheckpointException`; session/epoch/storage errors retain existing types and fixed safe storage messages.
- Read selected-account outbox presence without parsing/filtering/claiming/freezing/updating rows. Foreign-account rows
  do not count. This observation is not an admission to send or publish and does not require the account outbox to be empty.
- Immediately before the last-success write retire `currentPullCheckpoint = null`, even for equal timestamps. Never
  restore on rollback/uncertain commit. Add a narrow DAO update of ONLY `last_success_millis` with exact account/cursor/
  previous-last-success predicates (null-safe). Never `upsertSyncState`, insert/replace a row, advance cursor, infer a push
  boundary or mutate canonical/cache/domain/outbox/watermarks/device/catalogue/other-account state.
- Require update count 1. Reread and require account/cursor unchanged and last-success exactly supplied time. Recheck
  baseline/session/epoch/live capability/cancellation and selected-account outbox observation before commit; trigger
  tampering fails closed and rolls back uncommitted effects. No uncertain return may claim recorded success.
- Only after returned commit and active resumption install/return a fresh `PullCheckpoint` binding the new full row,
  plus the observed presence flag. Successful same-run follow-on checks/commits use that new token; old tokens stay stale.
  Failed/uncertain commits need durable reread; previous acknowledged heads/complete batch boundaries survive.
- Owned SQLite failures use existing `PullStorageException` with no raw cause. External callback/clock failures preserve
  provenance. Cancellation always propagates; canceled-child write tokens stay retired in a surviving parent run.

## Independent roles and allowed files

Both roles read this spec and relevant guide/workflow/architecture/testing/design/implementation/shared product/full
contracts. Source boundaries apply equally to LSP, raw search/diffs, generated classes, logs/reports and decompilation.
Use one **fresh native Pi meta/muse-spark-1.3-contributor/high** session per role; completed R07b sessions MUST NOT be
reused. Asynchronous reports only: `herdr agent prompt gym-coordinator "..."`, no `--wait`, agent wait or acknowledgment
polling. Initially no contributor has a Gradle slot. Coordinator alone owns shared build/harness/docs/integration review.

**Worker read:** production `data/sync/` and `data/gym/` only as needed, plus the documentation above; no tests/fixtures/
reports/QA work/diffs/compiled tests. **Worker write ONLY:**

- `data/sync/engine/PushDeliveryLoop.kt`
- `data/sync/engine/PullDeliveryLoop.kt`
- new `data/sync/engine/SyncDeliveryLoop.kt`, `SyncDeliveryResult.kt`
- `data/sync/store/PushStore.kt`, `PullCheckpoint.kt`, `PullCheckpointStore.kt`
- `data/gym/SyncDao.kt`

No entities/schema/dependency changes; private focused helpers in these files only. Ask coordinator before any extra path.

**QA read:** existing client `app/src/androidTest/`, offline `app/src/test/` excluding backend, exported v10 schema and
above docs. No production declarations/implementation/generated classes/diffs/Worker work. **QA write ONLY new:**

- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/SyncDeliveryTest.kt`
- `.../SyncDeliveryFenceTest.kt`
- `.../PullTailCommitTest.kt`
- `.../SyncDeliveryTestSupport.kt`

Use real Room, no DAO/run mocks or duplicated engine. Missing signatures/harness facts go to coordinator, never opposite
source inspection. Coordinator reviews both complete diffs and expected oracles independently.

## Source-independent harness facts

- `GymDatabaseTest` supplies in-memory `database` and closes after each case. Existing `claimPushStore(session,owner)`,
  `claimSession()`/`claimOwner()`, `pullSeedBaseline(cursor,lastSuccessMillis,account,cacheEpoch,baselineComplete)` work.
  `pullSeedBaseline` DELETES all cache rows; seed other scope after it. All callers for a physical DB share explicit owner.
- `ackCaptureWorkoutHead(mutationId=CLAIM_MUTATION_1,rootId=CLAIM_WORKOUT_A,name,extraRoots)` seeds a pending owned
  workout plus reservation/exact outbox through vetted capture. `ackAcceptedResult(mutationId,batch,status=ACCEPTED)`,
  `ackBatch(target,extraDeletes,batchId,cursor)`, `ackWorkoutChange(targetId,revision)` build complete literal outcomes.
  Push request contains `.envelope.mutationId`, `.requestJson`; assertions compare stored original bytes, not encoder output.
- Existing `pullBatch`, `pullDelete`, `pullUpsert`, `pullWorkoutRecord`/routine/custom and hand-authored raw fixtures
  support complete pull pages. Use fresh literal batch IDs/cursors and direct `PullResultModel(batches,nextCursor,hasMore)`.
  `GymSyncApi` overrides push/pull/snapshot; record ordered calls and assert HTTP outside `database.inTransaction()`;
  snapshot must fail if invoked. Gated calls use `CompletableDeferred`/virtual time, no sleeps.
- Existing `pullSyncCursor`, `pullLastSuccess`, `claimSnapshot` (all app tables), `ackMeta`, `ackCanonicalJson`,
  `ackWatermark`, `ackCacheRow`, direct DAO/SQL queries are observable oracles. No production encoder generates expectations.
  `pullBlockCursorWrites(trigger)` blocks ALL sync_state updates; reuse for tail rollback. `pullDropTrigger` drops it.
- `claimFileDatabase(context,name)`/`claimUniqueDbName` support close/reopen. Reuse transaction-executor admission barriers
  and separate query executor for ownership/queued tail races; query callbacks fire BEFORE SQL executes, so post-write
  cancellation must observe the write then cancel on a FOLLOWING query and assert hook fired/full rollback.
- Preserve inherited run context in children using explicit `coroutineScope` inside `withRun`; `runTest` receiver async
  can select parent context incorrectly. Cancellation identity belongs in actual caller, not across await stack recovery.
- Existing exception types: `StaleSessionException`, `StaleCanonicalEpochException`, `StalePullCheckpointException`,
  `PullStorageException`, `CanonicalConflictException`, `CanonicalStorageException`. API typed constructors are no-arg,
  except `Transient(retryAfterMillis:Long?=null)` and `RequestRejected(errorCode:String)`.
- Summary `.push`, `.pull`, `.hasOutstandingLocalWork`; store tail result `.checkpoint`, `.hasOutstandingLocalWork`.
  Existing `PullDeliveryStop.Tail` object remains unchanged. Direct primitive tests supply a freshly read/committed
  eligible checkpoint and explicitly model caller-proven tail; orchestration tests prove only validated driver terminal
  boundaries reach bookkeeping. Returned closed-run tokens are not reusable authority.

## Risk-selected coverage and REQUIRED gates

Risk: high-value sync ownership/transaction/cancellation integration, no schema or activation. New distinct obligations:

1. Successful accepted pushes precede exact checkpoint pull; push result cursors do not skip unseen boundaries;
   empty/nonempty valid tails record last-success, no projection writes; standalone push/pull behavior remains unchanged.
2. Representative push blocked/deferred/auth/rejection/malformed/oversize stops and budget limit do not pull/read tail;
   limit after final acknowledgment still cannot probe/authorize pull. Reuse established R06 failure matrix, not duplicate
   every model/codec branch. Strict input bounds fail before any side effect.
3. Pull budget/failure/malformed/invalid token/recovery never record last-success or snapshot; previous acks/batches survive.
4. New capture during pull: exact domain/outbox/metadata/watermarks preserved; valid tail can coexist with outstanding
   intent. Flag samples selected account only; false is not convergence. Foreign/local/catalogue/cache epochs preserved.
5. Uninterrupted shared owner across push -> pull -> tail, queued second caller/standalone push cannot enter the gap;
   cancel queued/HTTP/tail paths releases only after owned work settles. Late account/session/epoch/row/baseline changes
   fence EMPTY tail and failures as well as data; no late last-success or cleanup.
6. Real Room tail primitive full-row/null-last-success fences, exact timestamp including equal/backward clock, fresh
   chaining/retired-token rejection, trigger/update-count/post-write rollback and canceled-child token retirement.
   Reopen/durable reread distinguishes uncertain write attempts from actual commit evidence. Earlier durable work survives.
7. Fixed safe summary displays and unchanged public standalone APIs; reuse R07a/b whole-page/cycle/canonical fixtures/tests.

Iteration: serialized compile production/KSP + Android tests, focused new Room classes separately (not comma filters).
Acceptance: affected new classes and relevant existing regressions, **fresh full offline JVM** (`testDebugUnitTest --rerun`),
fresh production/KSP and Android-test compilation; **full UNFILTERED `connectedDebugAndroidTest --rerun`** on isolated
owned emulator; **explicit `assembleDebug lintDebug`**, lint XML review/no new warnings, APK review for no secrets/test-only
JDK/JDBC/backend helpers and byte-identical packaged catalogue/notices. Check source asset/schema unchanged, both full
diffs/indexes/untracked state, documentation links and `git diff --check` in BOTH repositories. Inspect executed nonempty
XML testcase names/counts/failures/errors/skips; cached task/compilation/filtered evidence is not a fresh full pass.

All Gradle runs require explicit coordinator-owned serialized slot; no concurrent contributor build. Test-owned emulator
may be provisioned/cleaned; never use personal AVD/device/config. Required unavailable gates/models are blockers returned
to user, never silently substituted/deferred. Existing transport/refresh wire behavior unchanged: no new backend gate
required here; isolated build/start/black-box HTTP ONLY if a changed integration defect makes it necessary, never backend
source/config/suite/personal fixtures. Real two-store engine convergence remains R10, API24/hosted CI/live Google/device
journeys remain their roadmap gates, not implied by this package. Strict legacy accepted auth replay remains unresolved.
