# R06d — Bounded durable push delivery loop

## Authority, goal and stop

The owner authorized R06d planning and implementation. Deliver an **inert**, explicitly constructed single-account/session
push driver over the accepted R06a/b/c seams. Serialize the whole bounded run, freeze before HTTP, send one exact head,
classify it, atomically acknowledge accepted results, and continue only after acknowledgment succeeds.

Stop after independent review, the declared R06 package gates and documentation closure. No R07 planning/implementation,
production factory/app activation, pull/bootstrap/publication, account transition, scheduler, backend source/suite,
personal configuration, live account/device journey, commit or push is authorized.

References: [approved push design](../../sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull),
[current seams](../../sync/implementation.md#durable-head-claiming-and-replay-admission),
[shared push contract](../../../shared-docs/docs/backend-contract/sync.md#push-protocol),
[exact identity](../../../shared-docs/docs/backend-contract/android-client-protocol.md#ordered-capture-and-durable-request-identity),
[workflow](../../coordinator-workflow.md), [testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates).
Shared v1 behavior is unchanged.

## Inventory and dependencies

R03b HTTPS transport, R04 vault/refresh/recovery seams, R05 storage/batches/publication and R06a/b/c are accepted.
Room is v10; no entity, DAO, migration, schema, bundled asset, dependency or build configuration change is expected.
Production remains signed-out/local-only. The existing PushStore requires an explicit shared PushRunOwner per physical
database. Its run mutex spans caller network work outside Room; run capability/session/epoch/exact-head fences and
conservative write-token consumption already exist. Canonical acknowledgment preserves newer local generations and
never advances cursor/last-success or publishes domain business data. Reuse, do not recreate these mechanisms.

The required durable Room/session journal remains R09; the real finite-edit/timer publication barrier remains R12a.
This driver is not durable sign-out authority or a convergence engine. Legacy malformed accepted auth replay remains
unchanged; exact uncertain refresh identity must never be rewritten.

## Normative new API

New production package: `com.dragosfotache.gymtracka.data.sync.engine`.

```kotlin
const val MAX_PUSH_DELIVERY_MUTATIONS = 100

class PushDeliveryLoop(
    private val store: PushStore,
    private val api: GymSyncApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun deliver(
        expectedCacheEpoch: Long,
        maxMutations: Int = MAX_PUSH_DELIVERY_MUTATIONS,
    ): PushDeliveryResult
}

data class PushDeliveryResult(
    val acknowledgedMutations: Int,
    val stop: PushDeliveryStop,
)

sealed class PushDeliveryStop {
    data object Empty : PushDeliveryStop()
    data object LimitReached : PushDeliveryStop()
    data class Deferred(val retryAfterMillis: Long) : PushDeliveryStop()
    data class Blocked(
        val reason: PushBlockReason,
        val errorCode: String? = null,
    ) : PushDeliveryStop()
    data object AuthenticationRequired : PushDeliveryStop()
    data object ReauthenticationRequired : PushDeliveryStop()
}
```

No additional dependency injection framework, scope/background launch, mutable cached run state, transport factory,
refresh coordinator or store abstraction is needed. Explicit constructor pairing is a caller obligation: `api` must
already bind the same authorized service/account/session as `store`. The driver never derives identity from Google,
reads credentials or silently follows another session. Future callers must share the store's required run owner; do
not manufacture another mutex/owner per invocation. Existing refreshing API can be supplied as GymSyncApi: it owns its
one unchanged access-401 retry; this driver adds none.

`maxMutations` must be in 1..100, validated before any clock/store/API work; expected epoch must be nonnegative, likewise
before work. Clock samples must be nonnegative or throw IllegalArgumentException; failure is not retry bookkeeping.
The bound is a client run-work budget, not a domain-size cap or wire batch size. Each HTTP push has exactly one request.
Result objects are immutable safe summaries. String forms may show counts, stop kinds, block reasons and retry times,
but never service-issued error codes, IDs, payloads, batches or credentials. Unknown service codes remain accessible
exactly via `Blocked.errorCode` and persisted by the store, not sanitized or printed.

## Delivery algorithm and state boundaries

1. Enter `store.withRun(expectedCacheEpoch)` **once** for the entire invocation. Queued/cancelled runs cannot claim or
   send until admitted; entry checks session/epoch. No nested run or enclosing Room transaction is accepted.
2. Sample the clock and call `run.claimHead(now)`. Empty/Deferred/Blocked immediately stop with corresponding summary;
   these paths do not send or mutate. Select the explicit account sequence head, never filter/bypass it.
3. For Claimed, obtain the defensive `claim.request` and call `run.checkClaim(claim)` immediately before `api.push`.
   Check caller cancellation before sending. HTTP receives `listOf(request)` and runs **outside Room**, inside the same
   owner lease. Never decode/re-encode or regenerate the frozen wire text, ID, effects or child plan.
4. Catch **only SyncApiException thrown by this push invocation**. Check active caller context before handling either
   result or failure. Cancellation (including injected cancellation from an otherwise active caller) and unexpected
   transport/programming/storage errors propagate; do not convert them to transient/malformed outcomes. Do not catch
   a SyncApiException from clock/claim/check/classification/acknowledgment as a transport failure.
5. On typed API failure, sample the clock for `run.handleFailure(claim, failure, now)`. Map RetryScheduled to Deferred;
   Retained to Blocked(RETAINED_ERROR, exact code); auth/reauth to their read-only summary. Always stop. No in-run delay,
   immediate retry, automatic refresh, successor admission, discard or cleanup. Wrong-operation read-token failures use
   the existing store's retained malformed-response behavior, not R08 recovery.
6. On returned response call `run.handleResult(claim, response)`. Rejected maps to Blocked(REJECTED, exact code);
   Malformed maps to Blocked(RETAINED_ERROR, "malformed_response"). Always stop for these branches.
7. Only Accepted calls `run.acknowledgeAccepted(claim, accepted)` with that same current claim. RetainedCorruption maps
   to Blocked(RETAINED_ERROR, "ack_context_corrupt") and stops. Acknowledged increments `acknowledgedMutations` by one
   and may continue. Never count HTTP200, classification, consumed tokens or uncertain commit/resumption as success.
8. After `maxMutations` successful acknowledgments, return LimitReached **without claiming/probing another head**.
   Exactly exhausting a queue at the limit still returns LimitReached; only an actual fenced empty selection yields
   Empty. The owner releases on all returns/errors after structured work settles. Another delivery invocation/new
   process uses fresh run/claim admission and unchanged frozen durable identity.

All fence, exact-row, owned storage and canonical validation failures propagate unchanged; no generic error swallowing
or same-run repair loop. Earlier committed acknowledgments remain durable if a later head fails. An acknowledgment
rollback retains exact frozen work, and recovery reclaims it; token consumption is never evidence of persistence.
A canceled/late transport callback cannot write through a dead caller/run. Sign-out/switch/generation/epoch changes
invalidate late success **and** typed failure application. Pre-send check is boundary admission, not a guarantee that
already-admitted network bytes can be recalled after a subsequent sign-out.

No push cursor/last-success/baseline/epoch changes, projection invocation, pull/snapshot calls, domain cleanup or
mutation capture occur in the driver. Empty is outbox emptiness at one fenced boundary, not complete sync/convergence.

## Existing API and harness facts (coordinator-supplied)

These facts are the contract for QA; production source inspection is not required or permitted.

- `PushStore(database: GymDatabase, session: RepositorySession, owner: PushRunOwner)`; `PushRunOwner()` owns shared runs.
  `withRun(expectedCacheEpoch: Long, block: suspend (PushRun) -> T): T` suspends; run created only there.
- `PushRun.claimHead(nowMillis: Long): PushHeadResult`, `checkClaim(claim: PushClaim)`,
  `handleResult(claim, result: PushResultModel): PushOutcomeResult`,
  `handleFailure(claim, failure: SyncApiException, nowMillis: Long): PushFailureResult`,
  `acknowledgeAccepted(claim, accepted: PushOutcomeResult.Accepted): PushAcknowledgmentResult` all suspend.
- `PushHeadResult`: Empty, Deferred(retryAfterMillis), Blocked(reason, errorCode?), Claimed(claim).
  `PushBlockReason`: REJECTED, RETAINED_ERROR, UNSUPPORTED_VERSION, CORRUPT, ATTEMPTS_EXHAUSTED.
  Claim has `row: SyncOutboxEntity`, `request: DurableRequestModel`, account/session/epoch getters. Its request getters
  are defensive. Frozen row admission increments attemptCount once; handle/ack writes consume the callback token.
- `PushOutcomeResult`: Accepted (private owned snapshot; getter outcome), Rejected(errorCode), Malformed.
  `PushFailureResult`: RetryScheduled(retryAfterMillis), Retained(errorCode), AuthenticationRequired,
  ReauthenticationRequired. `PushAcknowledgmentResult`: Acknowledged(canonical, acknowledgedRoots), RetainedCorruption.
- First transient due time = failureTime + max(1000ms, clamped hint); next due admission increments attempts and backoff
  becomes 2000ms; cap 300000ms. Overflow retains `retry_time_exhausted`. Deferred comparison is `due > now`.
- `GymSyncApi`: suspend `push(List<DurableRequestModel>): PushResultModel`, `pull(String?, Int): PullResultModel`,
  `snapshot(String?, Int): SnapshotResultModel`. No Room writes or credential orchestration in HTTP adapter.
  `SyncApiException` variants: Transient(retryAfterMillis: Long? = null), AuthenticationRequired,
  ReauthenticationRequired, RequestTooLarge, RequestRejected(errorCode: String), MalformedResponse,
  InvalidCursor, CursorExpired, InvalidSnapshotToken, SnapshotExpired. Only transient schedules retry.
- `DurableRequestModel(payloadVersion: Int, requestJson: String, affectedRoots: List<AffectedRootModel>,
  envelope: MutationEnvelopeModel)`; effects are local only. `PushResultModel(results: List<MutationOutcomeModel>)`;
  outcome `(mutationId: String, status: MutationStatus, errorCode: String?, batch: SyncBatchModel?)`.
  `MutationStatus`: ACCEPTED/DUPLICATE/REJECTED. `SyncBatchModel(batchId: String, cursor: String,
  changes: List<SyncChangeModel>)`; change `(aggregateType, aggregateId, operation, revision, record?)`.
  Accepted/duplicate-accepted needs a complete batch with target UPSERT (DELETE for aggregate.delete).
- Reuse test-owned `PushClaimTestSupport.kt`, `PushOutcomeTestSupport.kt`, `PushAcknowledgmentTestSupport.kt` and
  `GymDatabaseTest`. Those helpers expose real Room seeding/capture, exact row/full-database snapshots, literals,
  hand-built accepted batches and file reopen utilities; do not build another broad harness or mock DAOs.
  `ackSeedFenced()`, `ackCaptureWorkoutHead(...)`, `ackCaptureRoutineHead(...)`, `ackCaptureCustomHead(...)`,
  `ackAcceptedResult(mutationId, batch, status)`, `ackBatch(target, extraDeletes, batchId, cursor)`,
  `ackWorkoutChange(targetId, targetRevision)`, `ackDeleteChange(...)`, `ackRow(...)`, `ackSyncState(accountId)`,
  `claimSnapshot()`, `claimExec(sql,args)`, `claimUpdateCache(...)`, `claimFileDatabase(context,name)` are available.
  Existing tests demonstrate deterministic query callbacks/triggers, inherited coroutineScope gates and reopen.
- New request-byte assertions compare hand-authored text or exact pre-send stored `requestJson`, not generated expected
  bytes from production encoders. Expected canonical fields/errors/counters are independent literals. Controlled
  GymSyncApi callbacks are narrow timing/fault scripts, never a server simulator.

Ask the coordinator asynchronously for missing declarations/harness facts. No javap/decompilation, compiler guessing
loops or production LSP navigation for QA.

## Ownership and allowlists

Worker reads: this spec, approved local architecture/design/implementation docs, root guide, shared contract/product
specifications; production under `app/src/main/java/com/dragosfotache/gymtracka/data/sync/` and narrowly relevant
`data/gym/` database/DAO/entity declarations. Worker writes **only** new
`data/sync/engine/PushDeliveryLoop.kt` and `data/sync/engine/PushDeliveryResult.kt`. No existing store semantic edits
without coordinator approval. Worker must not inspect any tests, QA source/work/diffs, fixtures or acceptance reviews.

QA reads: this spec, root guide, testing strategy/workflow, shared normative contract/product/fixtures, and existing
`app/src/test/` and `app/src/androidTest/` sources. QA writes **only** new
`app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PushDelivery*Test*.kt` files (small support if needed),
and optionally new matching JVM tests under `app/src/test/.../data/sync/`. Do not add trivial shape/enum tests.
No production source/declarations/diffs/decompilation/Worker work or production-symbol LSP access.
Existing helpers are read/reused, not rewritten unless coordinator approves.

Coordinator owns specification/docs, shared build/harness integration, both diffs and final independent review.
Gradle slots are serialized by coordinator; neither contributor runs Gradle/build/device/backend commands before an
explicit slot. Fresh Pi Muse Spark 1.3 Contributor/high native sessions; corrections remain in those sessions.
Reports/requests via `herdr agent prompt gym-coordinator "..."` without wait or polling. No commits/pushes.

## Risk-selected tests and execution gates

Risk: sync orchestration can wrongly send successors, acknowledge uncertain/late work, swallow cancellation/storage
errors or expose false convergence despite safe component seams. Add distinct real-Room integration tests at the driver
boundary; reuse existing exhaustive codec, retry/classifier, claim/ack transaction and canonical tests rather than
repeat their full cross-family/field matrix.

New obligations (representative cases can share table-driven setup):

1. Ordered multi-head success through actual capture -> driver -> scripted API -> real canonical acknowledgment;
   transport observes exactly one committed frozen head outside Room each call. Check full canonical result/extra
   cloud deletion, exact removal, literal counters, preserved business projection/cursor/last-success/baseline/epoch.
2. Strict work budget/invalid arguments; Empty sends nothing; limit does not freeze/increment/probe successor, including
   exactly empty-at-limit behavior. A second invocation resumes remaining sequence.
3. Lost response transient then deferred/no call, due retry after real file reopen with same ID/exact custom-formatted
   bytes/effects/child identity and incremented admission; duplicate-accepted acknowledged once, successor only after
   that commit. Backoff scheduling uses failure time, not entry time; no waits/automatic retries.
4. Rejected and duplicate-rejected stop successors; representative malformed/count/ID/target result and oversized or
   unknown permanent failure retain head. A later invocation sends nothing around retained errors. Exact unknown code
   accessible but absent from string summaries. Auth/reauth stay resumable/read-only; no driver refresh retry.
5. While HTTP is gated, capture newer same-root intent. Acknowledging the old head preserves newer generation/domain
   metadata and bytes; budget leaves tail untouched. No domain publication or checkpoint advance.
6. Cancellation during HTTP plus genuinely late adversarial response cannot acknowledge; fresh invocation replays exact
   frozen identity. Unexpected API error propagates without invented retry writes. A typed failure racing canceled
   caller must not persist error/retry. Keep identity assertions inside actual caller where coroutine recovery matters.
7. Change account/session or cache epoch during gated HTTP; late accepted/typed failure writes nothing. Deterministic
   pre-send invalidation via an appropriate Room query/callback gate proves no API admission after stale check.
8. Two delivery callers sharing one owner cannot overlap transport; canceled queued caller creates no extra admission;
   ownership releases on stop/fault/cancel so later caller resumes. Use explicit gates, no sleeps/polling agent output.
9. Triggered acknowledgment rollback (canonical/removal/metadata commit failure) propagates, retains exact frozen row,
   blocks successor sending in that invocation; next fresh admission/duplicate result can recover. Representative
   invalid acknowledgment context returns retained corruption rather than counting success.

Iteration: compile production, compile instrumentation; run new focused driver classes and failing/affected regressions
in serialized slots. Slice/package acceptance: fresh full offline JVM, new focused driver instrumentation, **full
unfiltered instrumentation**, debug assembly and lint, exact executed XML inspection, both `git diff --check`, source/
schema/asset preservation and no production construction review. R06 completion is the declared milestone for the
previously deferred broad Android gates. Existing real-service adapters/contracts are unchanged: no new interoperability
suite or backend launch is required for this client-scripted driver slice. Actual real-HTTP/Room engine convergence
remains R10, API24/hosted CI/live Google/device/activation gates remain later milestones, never inferred from this pass.

Use only an isolated test-owned emulator; no owner phone/account or personal emulator wipe/configuration. If a required
gate is unavailable/failing, keep it unverified/in progress and report the blocker, never silently claim acceptance.
