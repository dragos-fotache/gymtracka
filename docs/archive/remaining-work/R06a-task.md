# R06a — Durable head claiming and exact replay

**Status: accepted 2026-10-05; historical specification.** See [acceptance evidence](R06a-review.md).
Original owner authorization covered this first bounded R06 slice only; this archived text grants no new authority.
Coordinator owns this normative contract, integration and acceptance. Follow the
[workflow](../../coordinator-workflow.md) and [documentation rules](../../README.md).

## Goal and stop boundary

Deliver an inert Room-backed push-claim boundary: serialize one account/session run, select the explicit
sequence head, freeze it durably before returning its exact request, and fence replay/late callback admission.
A future caller can perform HTTP outside Room while holding this run's ownership. This slice does not construct
that caller in the app and does not implement outcome handling.

**Non-goals:** canonical outcome application, acknowledgment/removal, rejection/error persistence, backoff calculation,
automatic HTTP/retry loops, pull, bootstrap, projection publication, session transitions/vault authority, UI,
WorkManager, backend changes, deployment, commits or pushes. R06b/c remain future authorized specifications;
no successful push outcome can remove work in R06a. Returning a claim never means delivered or converged.

## Authority and current inventory

Read [architecture](../../architecture.md), [implemented capture](../../sync/implementation.md#room-and-local-capture),
[approved push design](../../sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull),
[testing strategy](../../testing-strategy.md), and the finalized
[shared contract](../../../shared-docs/docs/backend-contract/README.md), especially
[ordered durable identity](../../../shared-docs/docs/backend-contract/android-client-protocol.md#ordered-capture-and-durable-request-identity)
and [exact request identity](../../../shared-docs/docs/backend-contract/service-protocol.md#request-identity-and-atomicity-t3t5).
No shared behavior is reopened.

Current Room version is 10. Reuse `sync_outbox.state`, `attempt_count`, scheduling/error columns, device session,
and `sync_account_cache.cache_epoch`. No entity/schema/version/asset change is required or authorized.
Existing `SyncStore` captures edits transactionally; only never-sent pending tail autosaves coalesce.
`readDurableOutboxRow` validates column/wrapper/envelope identities and preserves inner request bytes.
R05 canonical/publication stores and R04 HTTP/refresh/session components remain unchanged and inert.

## Concrete public contract

New declarations live in `com.dragosfotache.gymtracka.data.sync.store`:

```kotlin
class PushRunOwner

class PushStore(
    database: GymDatabase,
    session: RepositorySession,
    owner: PushRunOwner,
) {
    suspend fun <T> withRun(
        expectedCacheEpoch: Long,
        block: suspend (PushRun) -> T,
    ): T
}

class PushRun /* no public constructor */ {
    suspend fun claimHead(nowMillis: Long): PushHeadResult
    suspend fun checkClaim(claim: PushClaim)
}

sealed class PushHeadResult {
    data object Empty : PushHeadResult()
    data class Deferred(val retryAfterMillis: Long) : PushHeadResult()
    data class Blocked(
        val reason: PushBlockReason,
        val errorCode: String? = null,
    ) : PushHeadResult()
    data class Claimed(val claim: PushClaim) : PushHeadResult()
}

enum class PushBlockReason {
    REJECTED, RETAINED_ERROR, UNSUPPORTED_VERSION, CORRUPT, ATTEMPTS_EXHAUSTED,
}

class PushClaim /* no public constructor, no public copy */ {
    val accountId: String
    val sessionGeneration: Long
    val expectedCacheEpoch: Long
    val row: SyncOutboxEntity
    val request: DurableRequestModel
}

class StalePushClaimException : IllegalStateException
class PushStorageException : IllegalStateException
```

The exceptions use fixed safe messages and no raw causes. Existing `StaleSessionException`,
`StaleCanonicalEpochException`, and `CanonicalStorageException` remain the fence-error types.
Programming misuse (closed/wrong/nested capability, enclosing Room transaction, negative epoch/time)
uses `IllegalArgumentException` or `IllegalStateException` as appropriate, without payload text.
Do not expose models, stored requests, domain values or error messages through diagnostic `toString` methods.
`PushClaim` is an opaque capability, not caller-forgeable acknowledgment authority. Constructor visibility here is
Kotlin API visibility (internal implementation construction is allowed), not a JVM-reflection ABI requirement;
QA must not inspect production declarations through reflection. Immutable row fields may be
exposed; its request must be defensively owned: mutating any exposed decoded list must never change the retained
claim identity or later request getters. Fresh decode of its private exact snapshot is an acceptable simple solution.

### Single-process ownership

- `PushRunOwner` holds the shared coroutine Mutex. Every future activation/engine/scheduler for the physical database
  must use the same application-owned owner. Injection is required; no permissive default owner, static database
  registry, multiprocess lease, scheduler or app singleton wiring is added here.
- Stores sharing the owner serialize the entire `withRun` block, including caller-owned HTTP outside Room.
  Cancellation while waiting performs no Room work. Cancellation/failure closes the capability and releases ownership
  in `finally`; structured children must finish/cancel before another run enters.
- Reject nested `withRun` before waiting on the mutex (including nesting a different owner); avoid deadlocks.
  A private coroutine-context marker may implement this and bind capability use to the admitted run.
- Reject entry from an active Room transaction. Acquire ownership before brief fenced Room checks; no Room transaction
  or additional coordination lock spans the caller block. `withRun` checks session/epoch together inside a brief Room transaction before invoking that block.
- A run/capability from a closed block or another admitted run cannot claim or validate work, even when account,
  generation and epoch happen to match. Check caller cancellation before admission/return and inside Room seams.
  Child coroutine use within the structured run is allowed, but escaped/late use after closure fails.
- This is an explicit single-process primitive, not R09 durable session authorization. Device identity alone does not
  reconcile the encrypted vault. Production constructs neither this store nor an engine.

### Fences

Require nonnull account and positive session generation at construction, and nonnegative expected cache epoch/time.
The storage account identity follows existing `RepositorySession`: nonblank opaque service identity, not an invented
UUID restriction. Inside every Room operation, compare the immutable account/generation against `sync_device_state`
(absence is signed-out null/0), then check the account epoch. Missing account cache means epoch zero; negative counters
or `materialized_epoch > cache_epoch` fail using the accepted canonical storage check. Reuse that focused check.

Never follow a new account/generation/epoch silently. An intervening same-account edit may advance generation
watermarks/append work without advancing the cache epoch: that is not a stale claim by itself. Publication advances
cache epoch, so its old callbacks must fail. Recheck fences and open/admitted run state after claim writes and before
transaction completion. A late validation after sign-out/switch/publication fails without changing any storage.

### Explicit-head algorithm

One `claimHead(nowMillis)` performs a fenced Room transaction:

1. Read only the selected account's lowest sequence row, **without a state/error/due-time filter**.
   Foreign rows never compete with this account's head. Empty account returns `Empty`, with no storage creation.
2. Validate scheduling/state bookkeeping. Positive sequence, nonnegative attempt count and nonnegative nullable retry
   timestamp are required. States are exactly pending/frozen/rejected. A pending row with nonzero attempts is corrupt
   (possibly transmitted work must never become coalescible). Frozen rows with zero attempts remain compatible with
   the old primitive `freezePendingMutation`; they are already possibly transmitted and must replay unchanged.
3. A rejected head returns `Blocked(REJECTED, original errorCode)` without parsing/repairing its payload. Other heads
   with any retained error code/message return `Blocked(RETAINED_ERROR, original errorCode)`. No stored error message
   is exposed. Invalid bookkeeping returns `Blocked(CORRUPT)`. These checks never select a successor.
4. Read the full durable wrapper with the existing validated row reader. Unknown column/wrapper versions return
   `Blocked(UNSUPPORTED_VERSION)`; malformed JSON, duplicate members, forbidden fields, invalid envelope/effects or
   row/target identity mismatch return `Blocked(CORRUPT)`. Preserve all original data; do not decode a bare mutation,
   normalize JSON, repair versions, regenerate IDs, or consult current domain shape to reconstruct the request.
5. If attempts equal `Int.MAX_VALUE`, return `Blocked(ATTEMPTS_EXHAUSTED)` without overflow or any change.
   Otherwise, a valid retry time strictly greater than now returns `Deferred(the stored absolute timestamp)`.
   Equality is due. Do not calculate a new delay, compare cursors, or bypass the head.
6. Atomically set state to frozen and increment attempt count by exactly one, for pending **or already frozen** heads.
   Use an affected-row-count-checked DAO update within the same transaction. All other columns stay byte-for-byte/
   value-for-value unchanged. The updated row must still be the account's head. Recheck fences/capability before commit.
7. Return `Claimed` only after commit, carrying the new frozen row and exact durable request. Freeze means possibly sent
   forever, even when the caller never opened a socket. Count is claim/admission count, not proof of HTTP transmission.

No blocked/deferred/empty result changes any row, errors, generations or bookkeeping. Existing rejected/corrupt/oversize
context remains durable because its original row remains untouched; newly detected corruption is reported, not silently
persisted as a redesigned error state. R06b owns outcome/error/backoff writes.

### Exact claim validation and replay

`checkClaim` is read-only and fenced inside Room. Require the exact originating live run capability. Compare the current
explicit head with **every field** of the claim's frozen row, including account, sequence/mutation ID, kinds/target,
version, exact wrapper JSON, frozen generation, state, attempt count, retry/error context. The unchanged wrapper binds
all affected roots and inner request bytes. Missing/replaced/nonhead/tampered/reclaimed rows throw
`StalePushClaimException`; never substitute the new head or repair/remove the old row.

Issuing another claim increments the durable attempt count, invalidating older claim callbacks even with the same wire
identity. A new run/process claims the same retained frozen head with unchanged mutation JSON/effects/IDs, but obtains
new admission authority. Old run handles never become valid in the new run.

New local edits/semantic commands may append/coalesce **later** never-sent work while an earlier frozen head exists.
Those edits do not invalidate an unchanged current head or overwrite its request; generations need not equal the newest
visible root. This slice never marks roots synced, acknowledges later work, applies returned batches or publishes cursors.
A successful `checkClaim` is only a check at that transaction boundary; R06b/c must recheck atomically with result writes,
not rely on a prior check followed by an unfenced transaction.

Catch only owned storage/model failures where classification is required. Preserve cancellation identity/precedence;
Room rollback must leave both freeze and increment unchanged on failed/cancelled transactions. SQLite failures caused
by claim operations map to `PushStorageException` without raw causes. Exceptions from the caller's `withRun` block
(including its own SQLite failures or transport errors) propagate as caller errors, not mislabeled storage failures.

## Contributor access and ownership

Both contributors receive this same specification. No contributor may inspect the other layer, generated/decompiled
classes, whole working-tree diffs, build reports revealing the other layer, or another agent's output. LSP is subject
to the same boundaries; QA must not use production symbol lookup/hover to fill API gaps. Ask coordinator for facts.

**Worker read:** root `AGENTS.md`; this spec and linked documentation; production `app/src/main/java/.../data/`;
Gradle/configuration declarations needed for compilation. No Android test/fixture/report/archive source or QA work;
shared contract examples remain documentation, not Android test fixtures.
**Worker write only:**
- new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/PushStore.kt`;
- new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/PushClaim.kt` if useful;
- focused query additions in `app/src/main/java/com/dragosfotache/gymtracka/data/gym/SyncDao.kt`.
No changes to existing capture/canonical/publication behavior, entities, schema, assets, app construction or credentials.

**QA read:** root `AGENTS.md`; this spec and linked documentation; existing `app/src/test/` and `app/src/androidTest/`
(excluding backend fixtures); build/dependency/config declarations. **No production source/declarations/diffs**, including
new PushStore/DAO source, KSP/class output or Worker work. Execute production through the supplied public contract only.
**QA write only:** new `PushClaimTest.kt`, `PushClaimOwnershipTest.kt`, `PushClaimReplayTest.kt`, and
`PushClaimTestSupport.kt` under `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/`;
optional new `PushClaimContractTest.kt` under `app/src/test/java/com/dragosfotache/gymtracka/data/sync/`.
Do not weaken or rewrite existing tests. These filenames are a permitted set, not mandatory class splitting.

**Coordinator:** owns shared build/harness/config/doc changes, reviews both layers, supplies contract clarifications and
runs final gates. Contributor completion is not acceptance. Gradle/build slots are explicitly granted and serialized;
no concurrent Gradle commands. Report completion/blockers asynchronously to `gym-coordinator` using
`herdr agent prompt gym-coordinator "..."` with no wait flags. No polling/waiting for the other contributor.

## QA harness facts (contracts, not permission to read production)

Packages: Room database/entities/DAOs are `data.gym`; claim/session/capture declarations are `data.sync.store`;
wire models are `data.sync.model`; API is `data.sync.api`; codecs are `data.sync.codec`.
All package prefixes are `com.dragosfotache.gymtracka`.

- Extend existing `data.gym.GymDatabaseTest` for real in-memory Room; protected `database` is created/closed by JUnit.
  For disk reopen use `Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymDatabase::class.java,
  uniqueTestName).build()` with an isolated test-owned path; close owned coroutine work before database cleanup.
- `GymDatabase.syncDao()` exposes suspending `insertOutbox(SyncOutboxEntity): Long`,
  `getOutboxForAccount(accountId): List<SyncOutboxEntity>`, `getOutboxMutation(mutationId, accountId): SyncOutboxEntity?`,
  `upsertDeviceState(SyncDeviceStateEntity)`, `getDeviceState()`, `upsertSyncState(SyncStateEntity)`, `getSyncState(accountId)`.
  Existing unsafe delete/freeze DAO primitives are fixture-only, not application claim paths.
- `SyncOutboxEntity(sequence: Long = 0, mutationId: String, accountId: String, aggregateType: String,
  aggregateId: String, mutationType: String, localGeneration: Long, payloadVersion: Int = 1, payloadJson: String,
  state: String = "pending", attemptCount: Int = 0, retryAfterMillis: Long? = null, errorCode: String? = null,
  errorMessage: String? = null)` is a data class. Auto sequence is assigned on insert; its constructor object still has 0.
- `SyncDeviceStateEntity(id: Int = 1, activeAccountId: String? = null, cachedAccountId: String? = null,
  sessionGeneration: Long = 0, cacheState: String = "local_only")`; `RepositorySession(accountId: String?,
  sessionGeneration: Long)`; `SyncStateEntity(accountId: String, cursor: String? = null,
  lastSuccessMillis: Long? = null)`.
- `database.syncCaptureDao().getWorkoutMetadata(id)` returns nullable metadata exposing accountId, serverRevision,
  localGeneration, syncState and deletedAtMillis (no id property). `database.inTransaction()` is an allowed execution
  API for test transport's no-HTTP-inside-Room assertion.
- `database.syncCanonicalDao().getAccountCache(accountId)` and `getRootsForAccount(accountId)` are read-only snapshot APIs.
  Account cache SQL fields: account_id, baseline_complete (default 0), cache_epoch (0), projection_dirty (1),
  materialized_epoch (0). SQL fixture inserts/updates via `database.openHelper.writableDatabase.execSQL` are permitted.
  Use SQL triggers for freeze rollback/counter cancellation faults; never mock a DAO.
- All unchanged-table assertions may use direct real SQLite queries. Domain tables include workouts, workout_exercises,
  workout_sets, routines, routine_exercises, routine_sets, exercises/relationships, sync_canonical_roots,
  sync_account_cache, sync_state, sync_device_state and catalogue_state. Test fixture SQL is not production mutation code.
- `PushClaim.request` exposes `payloadVersion`, `requestJson`, `affectedRoots`, `envelope`; effects expose typed
  aggregateType/aggregateId/localGeneration. Author expected wire text and wrapper fixtures independently. `JSONObject`
  may quote that expected text into a wrapper; do not generate expected request bytes with production encoders.
- Capture regression can reuse test-owned fixtures or exercise `SyncStore(database, session, newMutationId)` and its
  `withFencedTransaction { tx -> tx.captureEdit(rootType, rootId, kind, changed, extraRoots = emptyList()) { payload } }`.
  A simple no-child workout input is `WorkoutInputModel(id, name, "active", startedAtMillis, emptyList())`, supplied in
  `MutationPayload.WorkoutUpsert(input)`. Seed `WorkoutEntity(id, name, startedAtMillis, accountId = account,
  localGeneration = 0)` using `database.workoutDao().insertWorkout`. Root generations/capture watermarks advance together.
- `GymSyncApi.push(List<DurableRequestModel>): PushResultModel`; also implement `pull(cursor, limit)` and
  `snapshot(pageToken, limit)` to fail if unexpectedly called. This test-only narrow transport can gate entry, inspect
  the already committed frozen row from another transaction, throw a cancellation/transient error, or return a literal
  accepted/duplicate/rejected outcome. No production HTTP driver is added by this slice.
- Test harness flow: `store.withRun(epoch) { run -> val result = run.claimHead(now); if (result is Claimed) {
  run.checkClaim(result.claim); api.push(listOf(result.claim.request)); run.checkClaim(result.claim) } }`.
  No acknowledgment is performed; all results leave the row frozen. This proves only claim/replay/admission, not delivered
  outcomes or service dedup correctness. Real service original-outcome interoperability/convergence remains later work.

If further signatures are needed, report a blocker rather than discovering production declarations.

## Independent acceptance cases

Real Room tests with literal expected values and deterministic gates are mandatory. Cover:

1. Selected-account sequence ordering across states/types, foreign account isolation, empty/no-cache/no-side-effects.
2. Pending freeze + increment before caller transport entry; exact whitespace/order/escapes/numeric spelling retained;
   all affected roots, multi-root start effects and copy/history child plans preserved.
3. Existing frozen head replay (including legacy attempt zero); repeat/reopen/new-owner recovery keeps identical wire
   bytes/ID/effects, increments admission count, and never becomes coalescible again.
4. Rejected/unknown code, retained error/413, unsupported column/wrapper versions, malformed/duplicate JSON,
   row/envelope/effect mismatch, unknown state, invalid sequence/counters/retry timestamp and exhausted count: no
   successor admitted, no repair/deletion/error-message leak, whole database unchanged.
5. Deferred head blocks a later due row; boundary equality admits; negative caller time/epoch rejects before mutation.
6. Lost response, cancellation before transport and during transport, thrown caller fault, response after cancellation,
   and simulated disk reopen/process restart preserve uncertain work unchanged. No HTTP in a Room transaction.
7. Shared owner serializes two stores/runs including caller HTTP; queued cancellation does no work; nested entry and
   closed/wrong-run capabilities reject; release on all failure paths; no timing sleeps.
8. Session account/generation/absence changes and cache epoch changes before claim and after transport reject late
   validation; malformed cache counters fail closed; other account/cache/cursor/last-success/domain rows unchanged.
9. Exact check rejects each independently tampered row field, removed/new head and prior admission count; claimed
   request cannot be modified through exposed lists. New later local edits preserve the frozen predecessor and do not
   invalidate it, but remain pending/protected. No root is marked synced.
10. Real SQLite trigger failure after the UPDATE and cancellation/failed fenced transaction roll back both freeze and
    increment. Caller-origin exceptions retain provenance; owned SQLite errors expose fixed safe errors.

Run focused new-class checks first, then fresh Kotlin/KSP, full offline JVM, Android-test compilation, assembly/lint,
and unfiltered instrumentation when the isolated emulator is available. Inspect executed XML names/counts/skips.
No schema change means existing migration regressions still run but no fabricated new migration gate is claimed.
Check both repository diffs, schema/asset preservation and `git diff --check`. Missing API 24/live backend/Google/device
journeys remain unverified; neither cached nor filtered runs count as a fresh full-layer pass.

## Completion

Coordinator reviews both complete layers, resolves defects without weakening independent oracles, and records actual
commands/results/unverified gates in `R06a-review.md`. Extract durable boundaries into implementation/testing docs,
update the roadmap and replace the handoff; archive this completed spec/review only after acceptance.
**Stop after R06a.** No automatic R06b, engine/outcome activation, commit, push or deployment.
