# R06b — push outcomes and durable retry/error handling

**Completed and accepted 2026-10-05 within the R06b boundary.** See the [acceptance review](R06b-review.md).
Android HEAD at pickup: `ae4069f`; shared contract HEAD: `31c969f`. Both working trees were clean.
This is the delivered production/independent-QA contract, not authorization for another slice.
Follow [workflow](../../coordinator-workflow.md) and [testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates).

## Goal and stop

Extend the inert R06a run capability to classify complete single-head push outcomes and durably record rejection,
transient retry scheduling, and retained top-level failures under exact claim/session/cache fencing. Return an owned
validated accepted outcome for the later atomic acknowledgment slice, **without applying it**.

Stop after independent review, scoped real-Room verification and full offline JVM acceptance. R06c owns atomic canonical
application plus exact acknowledgment; the bounded delivery loop is subsequent R06 work. No HTTP driver/engine,
production construction, account UI/activation, pull/snapshot, publication, schema/entity/migration change, vault/session
transition, scheduler, backend work, live account/device action, commit or push is authorized.

## Foundations and invariants

Reuse Room v10 `sync_outbox`, `PushStore`, `PushRunOwner`, `PushRun`, `PushClaim`, the existing strict durable reader,
protocol validators/codecs and canonical ownership helper. The physical database has one explicitly shared run owner.
R06a's claim increments `attempt_count` once before returning; this is admission count, not proof of transmission.
The exact stored frozen row (including attempt count, sequence, version, wrapper/request/effects and all error/schedule
fields), originating live run and immutable account/session/cache epoch remain callback authority.

Sources: [implementation](../../sync/implementation.md#durable-head-claiming-and-replay-admission), approved design
[original-outcome rules](../../sync/integration-design.md#3-revision-deletion-and-original-outcome-rules) and
[push policy](../../sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull), plus the full shared
[contract](../../../shared-docs/docs/backend-contract/README.md), especially service-protocol.md, android-client-protocol.md,
sync.md and OpenAPI. Existing protocol tests cover complete aggregate validation; do not reproduce their full matrix.

All R06b result writes are one Room transaction: recheck live capability, durable session/cache fences and the exact
explicit head before the write, update exactly one row, recheck fences/live admission/cancellation and expected post-write
head before commit. A successful earlier `checkClaim` alone never authorizes a write. No filtering around blocked heads.
Concurrent newer tail edits remain byte-for-byte unchanged, as do domain rows, canonical ledger/watermarks, account cache,
cursor/last-success, device/catalogue and other-account work. Cancellation or owned SQLite failure rolls back uncommitted
writes; caller exception provenance stays unchanged. Never catch cancellation as a service failure.

## Concrete API contract

All new types/helpers live in `com.dragosfotache.gymtracka.data.sync.store`.
Add member methods to the existing `PushRun`:

```kotlin
suspend fun handleResult(claim: PushClaim, result: PushResultModel): PushOutcomeResult
suspend fun handleFailure(claim: PushClaim, failure: SyncApiException, nowMillis: Long): PushFailureResult
```

`nowMillis` must be nonnegative, rejected before writes. Neither method performs HTTP or delays. Same capability-use
rules as `claimHead`/`checkClaim`: another-run claim, escaped/closed capability, enclosing Room transaction or foreign
coroutine context rejects with `IllegalStateException`; exact-row mismatch uses `StalePushClaimException`; session/epoch
changes use existing fence exceptions; owned SQLite faults use existing fixed `PushStorageException` without raw causes.
Claim use must fail before mutating storage, including malformed-result and authentication branches.

```kotlin
sealed class PushOutcomeResult {
    class Accepted internal constructor(/* implementation-owned snapshot */) : PushOutcomeResult() {
        val outcome: MutationOutcomeModel // fresh defensive deep-owned getter
    }
    data class Rejected(val errorCode: String) : PushOutcomeResult()
    data object Malformed : PushOutcomeResult()
}
sealed class PushFailureResult {
    data class RetryScheduled(val retryAfterMillis: Long) : PushFailureResult()
    data class Retained(val errorCode: String) : PushFailureResult()
    data object AuthenticationRequired : PushFailureResult()
    data object ReauthenticationRequired : PushFailureResult()
}
internal fun pushRetryAtMillis(nowMillis: Long, attemptCount: Int, retryHintMillis: Long?): Long?
```

The pure helper requires nonnegative time and positive admission count, clamps hints into 0..300000 ms, and returns
null on checked timestamp-addition overflow. Caller-invalid arguments throw `IllegalArgumentException`. No constructor/
enum-only testing quota. Result `toString()` must not print batches, UUIDs, payloads or service-issued error-code values;
retained unknown codes remain accessible through their fields. Accepted snapshots are not acknowledgment authority:
R06c must independently recheck admission and fences atomically with its canonical/outbox writes.

### Complete result handling

Deep-own and validate caller-supplied result/batch/nested lists before the first suspension (existing codecs/helpers may
be reused). Accept exactly one outcome with the exact claimed mutation ID. Reuse full protocol validation; accepted or
duplicate accepted must contain the complete valid batch and no error. The batch must contain the envelope target,
DELETE for `aggregate.delete`, otherwise UPSERT. Do not require it to equal the local affected-root list: cloud sole-active
replacement can delete a different previously unknown root, and all extra valid changes remain in the owned batch.
Do not compare device clocks/cursors or reject an old positive-revision duplicate merely because it may be obsolete.

- Accepted / duplicate accepted: read-only fenced transaction; return `Accepted` preserving status and complete batch.
  Change **nothing**, including retry fields. Do not call CanonicalStore or delete the row. Mutating supplied models or
  any getter result cannot change a retained snapshot or a later getter.
- Rejected / duplicate rejected: store state `rejected`, original exact unknown nonblank `errorCode`, fixed message
  `Push mutation was rejected; recovery required.`, and null retry time. Return `Rejected(code)`.
- Empty/extra/mismatched outcomes, invalid batch/record/status combinations, or missing/wrong-operation target: store
  state `frozen`, code `malformed_response`, fixed message `Push response was invalid; recovery required.`, and null
  retry time; return `Malformed`. Preserve uncertain request identity; never turn malformed evidence into rejection/
  acknowledgment or synthesize another mutation. Model-validation details never become persisted messages.

### Failure handling and retry policy

| Failure | Persistence / return |
|---|---|
| `Transient` (offline/throttle/5xx) | Keep `frozen`; set absolute retry time, leave error code/message null; return `RetryScheduled(time)` |
| `AuthenticationRequired` | Read-only exact fenced check; return matching result; refresh remains existing session-layer responsibility |
| `ReauthenticationRequired` | Read-only exact fenced check; return matching result; never poison the outbox or clear data/credentials |
| `RequestTooLarge` | Keep `frozen`, code `request_too_large`, message `Push request was too large; recovery required.`, null retry time; return `Retained(code)` |
| `RequestRejected` | Keep `frozen`, exact original nonblank code, message `Push request was rejected; recovery required.`, null retry time; return `Retained(code)` |
| `MalformedResponse`, invalid/blank RequestRejected code, or any read-token failure incorrectly supplied for push | Keep `frozen`, code/message as malformed result above, null retry time; return `Retained("malformed_response")`; no bootstrap/stage cleanup |
| Retry timestamp overflow | Keep `frozen`, code `retry_time_exhausted`, message `Push retry time was exhausted; recovery required.`, null retry time; return `Retained(code)` |

Backoff after admission n: `min(1000 * 2^(n-1), 300000)` milliseconds, calculated without shift/multiply overflow even
at Int.MAX_VALUE. Delay is `max(backoff, clamped service hint)`, capped to 300000; no jitter/randomness/sleeps. Absolute
time uses checked addition; overflow blocks rather than wrapping to an immediately due retry. A completed handling
bookkeeping write attempt consumes that exact process-local claim token after its in-transaction exact pre-write
checks, before the update; another callback or `checkClaim` on it is stale. This must also hold when a nonnegative
clock rollback computes an identical retry time, and when a canceled structured child loses a commit/resumption
result while its parent run survives. Row equality or marking only after Room returns is insufficient. Serialize
competing handling callbacks. Do not undo token consumption after failure: rollback preserves uncommitted storage,
original exceptions/cancellation still propagate, and no persistence/ack success is reported. Reclaim the same frozen
request for a fresh admission before handling again. Accepted/auth read-only paths remain unconsumed. This conservative
process-local retry-token rule is not durable request identity or acknowledgment.
Replay when due obtains a new
claim/count but the same mutation ID, original wrapper/request text and child IDs. Restart resumes stored absolute due
time; pending never-sent successors cannot pass the frozen head. Retained failures/rejections block via existing R06a
selection; no retry reset/recovery/discard API is added here.

## Contributor ownership and harness facts

Worker read: this spec, AGENTS.md and applicable docs/shared contract; production under `data/sync/` and
`data/gym/` only. Worker write: `data/sync/store/PushStore.kt`, new `PushOutcome.kt` and optional new `PushRetryPolicy.kt`,
plus focused queries in `data/gym/SyncDao.kt`. No tests/fixtures/reports/QA output, generated source/decompilation or
whole-tree diff access. No other edits without coordinator approval. Prefer restricted file-scoped LSP; workspace
navigation that could disclose tests is forbidden.

QA read: this spec, applicable docs/shared contract, existing `app/src/test/` and `app/src/androidTest/` only.
QA write: new `PushOutcomeTest.kt`, optional focused `PushOutcomeRetryTest.kt`/`PushOutcomeFenceTest.kt` and
`PushOutcomeTestSupport.kt` under androidTest `data/sync/`, and new `PushRetryPolicyTest.kt` under JVM `data/sync/`.
No production declarations/implementation/diffs/generated/decompiled code or Worker output, including LSP.
Coordinator alone reviews both layers and owns docs/build/harness integration. Ask coordinator for missing facts.
Both roles use fresh Muse Spark 1.3 Contributor/high native sessions. Async reports to `gym-coordinator` via
`herdr agent prompt gym-coordinator ...` without `--wait`; no waiting/polling another role. No Gradle without a
coordinator-granted exclusive slot. Coordinator owns all acceptance/device runs initially.

Existing test-owned harness allowed unchanged:
- `GymDatabaseTest` exposes protected `database: GymDatabase` backed by real in-memory Room; setup/teardown automatic.
- `PushClaimTestSupport.kt`: `database.claimSeedDevice()`, `claimSeedCache(account,epoch,...)`, `claimUpdateCache(...)`,
  `claimSeedWorkout(id,account,name,generation)`, `claimSession(account="account-a",generation=7)`, `claimOwner()`,
  `claimPushStore(session,owner)`, `claimCaptureStore(session, mutableListOf(mutationIds))`,
  `claimCaptureStart(store,account,root,name,extraRoots=emptyList())`, `claimCaptureUpsert(store,account,root,name)`;
  captured rows are `SyncOutboxEntity`; `(run.claimHead(now) as PushHeadResult.Claimed).claim` selects authority.
- `claimSnapshot()` independently reads every application table; `claimExec(sql,args)`, `claimOutboxFor(account)`,
  `claimWrapRequest(innerJson,affectedRootsJson)`, `claimRootEntry(id,generation)`, `claimFileDatabase(context,name)`
  and existing controlled/adversarial transports support exact byte/reopen/race checks. Reuse rather than clone them.
- Outbox properties: sequence, mutationId, accountId, aggregateType, aggregateId, mutationType, localGeneration,
  payloadVersion, payloadJson, state, attemptCount, retryAfterMillis, errorCode, errorMessage.
  `database.syncDao()` exposes getOutboxHead/getOutboxMutation/getOutboxForAccount, insertOutbox, upsertDeviceState,
  getDeviceState, upsertSyncState/getSyncState; use real SQLite for adversarial triggers/fixtures.
- DTO constructors: `PushResultModel(results: List<MutationOutcomeModel>)`,
  `MutationOutcomeModel(mutationId, status: MutationStatus, errorCode: String?, batch: SyncBatchModel?)`,
  `SyncBatchModel(batchId,cursor,changes: List<SyncChangeModel>)`,
  `SyncChangeModel(aggregateType,aggregateId,operation: ChangeOperation,revision: Long,record: CanonicalRecord?)`.
  `decodePushResult(String)` accepts hand-authored valid wire fixtures (not expected encoder-generated output).
  Direct invalid DTOs can test the consumer boundary; existing tests document aggregate constructors.
- API failures are no-argument subclasses of `SyncApiException` except `Transient(retryAfterMillis: Long?=null)`
  (hint clamped 0..300000), and `RequestRejected(errorCode: String)`. No arbitrary message/cause input.
- `PushStore(database, RepositorySession(account,generation), sharedOwner).withRun(expectedCacheEpoch) { run -> ... }`
  is the sole live-run entry. Claimed getters preserve exact durable request JSON. Existing fence exceptions are
  `StaleSessionException` and `StaleCanonicalEpochException`; R06a defines `StalePushClaimException`/`PushStorageException`.

## Risk-selected verification

**Risk: high persistence/uncertain-write boundary; narrow inert scope.** Distinct independent new obligations:
1. Accepted and duplicate accepted complete multi-root snapshots are unchanged/read-only, including extra unknown cloud
   old-active deletion; response target operation/count/ID/content failures retain a safe frozen error, never acknowledge.
   One nested-input/getter mutation test proves deep ownership across a gated Room suspension.
2. Rejected/duplicate rejection and unknown codes persist exact code/fixed context and block a different-root successor;
   top-level permanent/413 stays frozen (uncertain), never normalizes identity or retries automatically.
3. Transient deterministic backoff/hints/caps/overflow at JVM helper layer; real Room integration proves due-time blocking,
   unchanged exact-byte/child-plan replay, admission-count progression and disk reopen scheduling. One clock-rollback
   case proves identical retry bookkeeping still consumes the claim and makes all later callbacks/checks stale.
4. Representative stale exact-row/count, changed session and epoch, foreign/closed capability and actual caller
   cancellation reject every handling branch without writes; include a late scripted response and newer tail edit.
5. Real SQLite trigger-induced failure and post-write fence/cancellation rollback prove that outcome/retry changes do
   not partially commit; no raw storage diagnostics. Independently snapshot unrelated tables/foreign/local data.
6. Auth/reauth are read-only and resumable without retained errors; wrong-operation token failures cannot bootstrap.

Prefer representative cases/table-driven subcases for shared paths; do not repeat the entire R06a capability matrix,
all 17 mutations, complete R05 graph/schema checks or generic HTTP error matrix. Existing prerequisite tests stay intact.
No sleeps, mocked DAOs, production-encoded expectations, reflective private API tests or backend-sized simulator.

Iteration: Kotlin/KSP compilation and focused new JVM checks; instrumentation compile, then one fully qualified class
filter at a time when ready. Slice acceptance: fresh full offline JVM plus all new outcome Room tests and existing
R06a claim/replay/ownership Room regressions (separate class commands; inspect actual nonempty XML names/counts/errors/
skips), `git diff --check` in both repos, unchanged entity/schema/asset/build/activation review, local docs/link checks.
Use task-scoped `--rerun`, not blanket dependency rebuilds, for JVM evidence. Coordinator owns test-only isolated emulator.

Deferred broad gates: unfiltered instrumentation + assembly/lint/APK checks at **R06 package completion**, real service
Room-backed convergence at core R10, API24/Google/phone/deployment at R17. This inert slice changes no packaging/security/
configuration/schema/HTTP boundary; scoped device verification is sufficient here, not a claim of full regression.
Unavailable mandatory scoped gates block acceptance. No new backend pass is required or implied.
