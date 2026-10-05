# R07a — Fenced incremental batch/checkpoint transactions

> Archived accepted specification. Current implementation is in [sync implementation](../../sync/implementation.md);
> this record grants no new authorization.

## Authority and stop

The owner authorized R07a implementation after agreeing to split R07 into a transaction seam (a), bounded HTTP pull
loop (b), and shared push-before-pull orchestration (c). This document is the normative assignment for both contributors.
Stop after independent review and the gates below. R07b/c, bootstrap, production construction/publication, commits,
pushes, backend work, configuration and live account/device actions are not authorized.

## Goal and non-goals

Deliver an inert, explicitly used Room boundary which reads an eligible incremental checkpoint, fences callback
admission, and commits one complete canonical batch together with its next pull cursor. Reuse R05b canonical arbitration
and R06's application-owned run owner. Do not introduce a parallel PullStore lock or a second owner.

No HTTP, pages, paging/token-cycle history, tail/last-success writer, retry scheduling, snapshot/expiry recovery,
publication, account transitions, UI, credentials, scheduler, entity/schema/migration change or production factory.
Tokens stay opaque. This store cannot authenticate tokens or prove server contiguity by parsing them: R07b must supply
complete batches in returned order and validate page/run progress; R07c must enforce push-before-pull policy.

## Current inventory and dependencies

- Room v10 already has `sync_state(account_id PK, cursor nullable TEXT, last_success_millis nullable INTEGER)` and
  separate `sync_account_cache(account_id PK, baseline_complete, cache_epoch, projection_dirty, materialized_epoch)`.
- `PushStore(database: GymDatabase, session: RepositorySession, owner: PushRunOwner)` admits `withRun(expectedCacheEpoch:
  Long, block: suspend (PushRun) -> T): T`. Signed-in account and positive generation are required. A shared owner Mutex
  spans the caller block including HTTP outside Room; nested/Room-transaction entry rejects; structured children settle
  before closure. Capability use checks its private coroutine-context marker and volatile open state.
- `PushRun` already has a short handling Mutex. Reuse it for checkpoint admission/check/commit so concurrent use within
  one run has one ordering. Existing push claims/outcomes/acknowledgments retain their semantics.
- `ownedCanonicalBatch(batch)` deep-owns and validates the entire typed batch synchronously using existing strict codecs.
  `applyOwnedCanonicalBatch(database, accountId, generation, epoch, owned)` applies one whole batch inside its caller's
  transaction, preserves watermarks, arbitrates revisions/family bindings and marks dirty when changes are selected.
  All-obsolete/equal batches are canonical no-ops. Unresolved references can be stored; whole-graph validation is later
  publication. Never call repository capture to apply remote data.
- `checkCanonicalFences` checks active account/generation, nonnegative epoch/materialized counters, materialized <= cache
  epoch and exact expected epoch. Stale session/epoch and malformed retained canonical evidence have existing exceptions.
- `SyncDao.getSyncState(accountId)` returns the nullable row; existing `upsertSyncState` remains for existing callers.
  Add a narrow cursor-only update for this seam, not a replacement/upsert which resets last-success.
- No production caller establishes a complete baseline. Tests explicitly seed a complete baseline and cursor. R08 owns
  creating/replacing them; R09 owns durable authority. Existing run fencing is not vault/Room durable authorization.

Normative references: [implementation](../../sync/implementation.md), [design §§2–4](../../sync/integration-design.md),
[shared pull protocol](../../../shared-docs/docs/backend-contract/sync.md#pull-protocol),
[opaque boundaries](../../../shared-docs/docs/backend-contract/service-protocol.md#pull-boundaries-and-retention-t2),
[client protocol](../../../shared-docs/docs/backend-contract/android-client-protocol.md#ordered-capture-and-durable-request-identity).
No shared-contract change is required.

## Concrete public contract

All new types are in `com.dragosfotache.gymtracka.data.sync.store`. Keep the existing `PushRunOwner`/`PushStore` names
for compatibility: the admitted `PushRun` now carries this additional engine transaction seam. No general owner rename
or driver refactor is needed in a. R07c will compose operations within one admitted run rather than nesting driver entry.

```kotlin
// Opaque immutable callback authority; no public constructor/copy, referential identity.
class PullCheckpoint internal constructor(/* private/internal binding */) {
    val cursor: String
    override fun toString(): String // fixed redacted display, no cursor/account/body
}

// Immutable owned commit summary; redacted display (checkpoint is redacted).
data class PullBatchCommitResult(
    val canonical: CanonicalBatchResult,
    val checkpoint: PullCheckpoint,
)

class StalePullCheckpointException : IllegalStateException
class PullStorageException : IllegalStateException

// Add these members to the existing PushRun:
suspend fun readPullCheckpoint(): PullCheckpoint?
suspend fun checkPullCheckpoint(checkpoint: PullCheckpoint)
suspend fun applyNextPullBatch(
    checkpoint: PullCheckpoint,
    batch: SyncBatchModel,
): PullBatchCommitResult
```

`PullStorageException` has the fixed message `Pull checkpoint storage operation failed`, no raw cause/suppressed database
exception. `StalePullCheckpointException` has the fixed message `Stale pull checkpoint`. Constructors need no configurable
message. Caller cancellation, existing model/canonical/fence errors and unexpected caller errors preserve provenance.
Do not wrap the enclosing caller block. New typed summaries must not print tokens, identities or domain bodies.

The new cursor-only DAO member is:

```kotlin
suspend fun advancePullCursor(accountId: String, expectedCursor: String, nextCursor: String): Int
```

Its SQL updates only `cursor` where account and previous cursor match, never inserts a missing row, and returns affected
count. Full row and baseline checks remain the transaction caller's responsibility. Keep Room version 10/exported
schemas/asset unchanged.

### Read/admission

1. Require live originating run context, no enclosing Room transaction and active caller cancellation checks. Serialize
   through the existing handling Mutex; invalidate the previous checkpoint token on a new read attempt (including a
   failed read). No stored data changes.
2. In one Room transaction check session/epoch fences, read selected-account cache and sync state coherently. A present
   state row with an empty (not whitespace-only) nonnull cursor or negative nonnull last-success is corrupt and throws
   `PullStorageException`, without repair. Opaque whitespace tokens are not trimmed or rejected as blank.
3. Return null if cache is absent/incomplete, state row is absent, or cursor is null. Null means bootstrap/baseline needed,
   never authorization to pull retained history with null input. Preserve every existing row and flag.
4. Otherwise return a fresh token privately binding this run, account, generation, epoch and the complete immutable
   observed sync-state row (including last-success). It becomes the run's one current checkpoint token. Recheck
   fences, eligibility/admission and cancellation before transaction completion and before return.

### Check/admission for later transport

`checkPullCheckpoint` is read-only. Reject foreign token/run use, closed/outside-run/enclosing-transaction use, consumed
or superseded tokens. Under the handling Mutex, inside Room, recheck session/epoch, complete baseline, exact complete
sync-state row and active capability/cancellation. Changed/deleted checkpoint row or lost baseline is
`StalePullCheckpointException`; changed session/epoch uses the existing fence exceptions. Success is evidence only at
that boundary; it cannot recall already-admitted network bytes or authorize later writes without transaction rechecks.

### Atomic next-batch commit

1. Deep-own/fully validate the supplied whole batch **before the first suspension**, even when its nested lists came
   from mutable callers. Reject a batch cursor equal to the input checkpoint cursor with `SyncModelException` (no
   progress). Compare exact token equality only; do not parse, trim, sort or lexically compare cursors.
2. Require originating live/current checkpoint and no enclosing transaction. Under the handling Mutex open exactly one
   Room transaction, rechecking fences, complete baseline, exact observed full sync-state row, token/admission and
   cancellation before any write.
3. Conservatively consume the checkpoint token immediately before the canonical/cursor write attempt. Consumption is
   process-local, survives rollback or uncertain commit/resumption, and is not persistence evidence. Retrying requires
   a fresh `readPullCheckpoint` of the durable state. A canceled structured child must not reuse the token while its
   parent run remains alive. Read-only checks never consume.
4. Apply the complete owned batch through the existing canonical writer. Then call cursor-only update from the exact
   checkpoint cursor to the batch cursor and require affected count 1. All canonical changes/dirty marking and cursor
   advancement share the same transaction. Recheck the full expected post-write row (old last-success unchanged),
   baseline, fences, live capability and cancellation before commit. A failed cursor update count, mismatched expected
   post-write row, or lost post-write baseline is `StalePullCheckpointException`, matching pre-write stale boundary
   classification. Changed session/epoch continues to use its existing fence exception; do not mask it as stale cursor.
5. Return the canonical result plus a fresh current token binding the new full state only after successful commit and
   active resumption. The old token remains stale. A failure cannot advertise a successful checkpoint. If synchronous
   commit occurs before cancellation is observed, do not claim rollback; reread durable state on recovery.
6. A valid batch whose revisions are all obsolete/equal still advances its pull cursor; preserve canonical no-op/dirty
   semantics. Canonical equality conflicts, family errors, malformed evidence, SQLite errors, cancellation or failed
   postchecks roll back the batch AND cursor if uncommitted. Earlier separately committed batches remain durable.

### Preservation boundaries

- Never advance from a push acknowledgment cursor or a page's `nextCursor` separately. This method receives one batch
  and commits only its own cursor. Empty tail has no batch to commit; checking retains its input boundary. No
  last-success update or convergence claim is introduced in R07a.
- No domain/root/child/business metadata, outbox identities/order/state/errors, local generations/watermarks, device
  identity, catalogue, other-account cursor/cache/canonical, baseline flag or account/materialized epoch changes.
  Selected-account canonical observations and their existing dirty marker are the only changes besides selected cursor.
- Outstanding account outbox work does not prevent this primitive storing canonical observations; it remains untouched
  and visible intent remains protected. R07c blocks ordinary pull around retained errors and orders push first. New
  captures during a later HTTP call cannot be overwritten by this canonical-only seam.
- Missing roots from an incremental batch never become deletion/snapshot_absent. Store explicit unknown-root DELETE
  evidence without fabricating a domain row. Intermediate unresolved dependencies are permitted, never published here.
- Same-checkpoint concurrent commits admit at most one: the second cannot overwrite the winner or skip a boundary.
  After rollback the caller may reread the same boundary and reapply the same batch; canonical replay arbitration is
  reused. No durable batch-ID ledger or cross-run cycle detection is added.

## Ownership and allowlists

Both roles read this specification, root AGENTS, docs README/workflow/testing-strategy, sync implementation/design,
architecture/product behavior and required shared backend-contract documents. These references are contracts, not
permission to cross contributor source boundaries. Prefer bounded mcpls navigation restricted to allowed files.

**Worker** reads production `data/gym/` and `data/sync/` as needed. Writes only:

- `app/src/main/java/com/dragosfotache/gymtracka/data/gym/SyncDao.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/PushStore.kt`
- new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/PullCheckpoint.kt`
- optional new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/PullCheckpointStore.kt` for focused helpers.

Worker must not inspect any tests/fixtures/reports, QA output, generated test classes or QA diffs. No production
API/schema changes outside the contract without coordinator approval. Compiler diagnostics restricted to production
are permitted during a granted production-only compilation slot.

**QA** reads existing `app/src/androidTest/` and `app/src/test/` client tests (exclude `backend/`), exported v10 schema
and this contract; never reads production declarations/implementations/generated production/diffs or Worker output.
Writes only new:

- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PullCheckpointTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PullCheckpointFenceTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/PullCheckpointTestSupport.kt`

Do not recreate production in a fake. Use real Room and independent expectations. Ask coordinator for missing
signatures instead of inspecting source or guessing. Coordinator owns docs/build/harness integration and both reviews.

### QA API/harness facts (source-independent)

- Existing `GymDatabaseTest` supplies a seeded in-memory `database`; see allowed test source for lifecycle.
- Existing `PushClaimTestSupport` supplies `claimSession`, `claimPushStore`, `claimOwner`, `claimSeedDevice`,
  `claimSeedCache`, `claimUpdateCache`, `claimExec`, `claimSnapshot`, `claimSeedWorkout`, captured start/upsert helpers
  and file-database helpers. Pass one shared owner explicitly for concurrency tests; helper default is fresh per call.
- Existing canonical/ack supports supply independently authored typed changes, raw JSON fixtures, direct database
  observations and capture helpers. Reuse, do not multiply domain/codec/family matrices already covered by R05/R06.
- `SyncStateEntity(accountId: String, cursor: String? = null, lastSuccessMillis: Long? = null)` is seeded through
  `database.syncDao().upsertSyncState`; read through `getSyncState`. `syncCanonicalDao().getRootsForAccount`,
  `getRoot(accountId,type,id)` and `getAccountCache` are existing read APIs. SQL schema/column names are available
  in the exported v10 JSON and existing tests. No new entity.
- `SyncBatchModel(batchId: String, cursor: String, changes: List<SyncChangeModel>)`;
  `SyncChangeModel(aggregateType: AggregateType, aggregateId: String, operation: ChangeOperation, revision: Long,
  record: CanonicalRecord?)`. Types are WORKOUT/ROUTINE/CUSTOM_EXERCISE and UPSERT/DELETE. `decodeSyncBatch(String)`
  accepts independently hand-authored wire input; never use the production encoder to generate expected JSON.
- Known fence exceptions are `StaleSessionException` and `StaleCanonicalEpochException`; conflict/malformed retained
  evidence uses `CanonicalConflictException`/`CanonicalStorageException`; incoming model uses `SyncModelException`.
- For owned-input race, reuse transaction-executor admission barrier + separate query executor and inherited
  `coroutineScope`/UNDISPATCHED techniques in existing tests. Query callbacks precede SQL; cancel on the query after
  an observed write, assert the hook actually fired, then compare complete persisted snapshots after rollback.

## Risk, independent test obligations and gates

Risk: high-integrity sync persistence without schema/production activation. Distinct new coverage:

1. Eligible read/check is side-effect-free; missing/incomplete baseline and missing/null cursor return null without
   repair; empty cursor/negative last-success fail closed; whitespace/opaque cursors preserve exact strings.
2. Complete multi-root replacement and representative custom/routine observations commit with exact cursor; unknown
   DELETE stores evidence, dependencies can arrive later, unrelated/missing roots never disappear. Domain/outbox/newer
   intent/generations/epochs/last-success/other scopes stay unchanged except allowed canonical/dirty/cursor fields.
3. All-obsolete/equal observations advance only cursor with existing canonical no-op semantics. Equal conflict rolls
   back. Earlier batch remains after a subsequent conflicting/storage-failed batch; retry after reread/reopen resumes
   from exact durable boundary, not page tail.
4. Invalid batch/no-progress fails without writes; caller mutation after suspension cannot change the owned batch.
5. Cursor write SQLite trigger failure and trigger-induced post-write cursor/last-success or session/epoch mismatch
   roll back canonical writes and checkpoint together; fixed safe error/no raw SQLite diagnostics.
6. Session switch/sign-out/generation/epoch, exact-row change/removal, lost baseline, foreign/superseded/consumed/closed
   capabilities and enclosing transaction reject. One same-token concurrent commit wins; shared owner still serializes
   runs, including caller gates outside Room. Reuse established run tests rather than duplicate their entire matrix.
7. Post-write cancellation rollback and consumed-token behavior while parent remains alive; fresh reread permits same
   durable identity. Cancellation while awaiting admission leaves no writes; leaked late callbacks cannot commit.

Do not impose test-count targets. Reuse existing canonical codec/arbitration/publication/capture/push tests for their
unchanged invariants, adding only seam integration cases. Every new test has a distinct observable oracle.

- Iteration: Worker granted `:app:compileDebugKotlin` only; coordinator compiles Android tests then executes focused
  new classes while iterating. All Gradle runs serialized by explicit coordinator slots. Contributors initially have
  **no Gradle slot**; report readiness/blockers asynchronously.
- Slice acceptance: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain`;
  fresh full offline JVM via `./gradlew :app:testDebugUnitTest --rerun --console=plain`; focused real Room execution
  of each new class separately using connected task class filters, plus affected existing canonical batch and atomic
  acknowledgment regression classes. Inspect actual nonempty XML names/counts/failures/errors/skips; no cached or
  compile-only execution claim. A required unavailable Room gate blocks acceptance.
- Full unfiltered instrumentation and debug assembly/lint/APK checks deferred to R07c package gate, absent an unexpected
  schema/packaging/config/security change bringing them forward. No backend/Google/API24/app activation claim here;
  engine convergence remains R10. An isolated test emulator may be used, never the owner's personal phone data.
- Before handoff inspect both trees/full production and test diffs, `git diff --check` and shared-docs diff check; validate
  changed documentation links. Record actual results/environment/unverified gates in one acceptance review, replace
  owning implementation/roadmap/handoff facts, archive accepted spec/review. Stop before R07b.
