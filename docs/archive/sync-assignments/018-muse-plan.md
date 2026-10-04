> **Unimplemented historical simulator proposal. Do not resume.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# Assignment 018 coordinator plan for Muse — prepared 2026-09-28

**PAUSED — historical plan, not delegation instructions.** The user approved prioritizing the real backend and retaining
the existing fake only temporarily during coverage transfer, then deleting the large simulator and its obsolete tests
under the [retirement gate](../sync-implementation-plan.md#fake-retirement-gate). This planned expansion remains paused.
Do not launch 018a/b/c from this document. Follow
the [current strategy](../sync-implementation-plan.md#approved-strategy-change--real-backend-before-further-simulator-expansion);
any targeted fake addition needs a concrete Android test requirement and a new bounded scope.

Status: **handoff plan only; no 018 implementation has begun**. The [018 specification](018-task.md)
and [backend contract](../../../shared-docs/docs/backend-contract/README.md) control behavior. The [017 review](017-review.md) is the
accepted code/test baseline. Assignment 018 completes the Android **test-only fake service**; it does not implement the
production sync engine or a backend.

## Instructions the coordinator gives at every gate

- Use one fresh Muse Spark Contributor 1.3 Hermes session for the **entire** named subtask, including its review
  corrections. Send only that subtask after the prior one is approved. Do not create `018a1/018a2` or start the next
  subtask in the same session. The coordinator independently reviews and closes each completed session before starting
  another.
- Orient Muse with this exact code map: `FakeSyncService.kt` owns roots, `executePush`, `commitBatch`, account log,
  `recordOutcome`/`replayOutcome`, `clockMillis` and `uuidProvider`; `FakeAccountSyncApi.kt` owns the bound-account
  `GymSyncApi` calls and page-limit recording. `ProtocolModels.kt` defines `PushResultModel`, `MutationOutcomeModel`,
  `SyncBatchModel`, `PullResultModel` and `SnapshotResultModel`; `SyncApiFailures.kt` defines top-level failure
  categories. Existing command tests and `FakeSyncFixtures.kt` are under
  `app/src/test/java/com/dragosfotache/gymtracka/data/sync/`. Full paths are in
  the [handoff](../../coordinator-handoff.md).
- Read the current diff and the contract before editing. Preserve all 17 approved command handlers, exact
  `DurableRequestModel.requestJson` bytes, the account-bound adapter, staged per-mutation commits, defensive copies, and
  test-only source-set isolation. Do not edit `app/src/main`, Room/schema, asset, dependency/build configuration,
  backend docs, HTTP, auth, UI or worker code in this assignment. A production boundary adjustment needs a demonstrated
  contract mismatch and coordinator review first.
- Write independent JVM assertions for full returned outcome/batch/page content and underlying state. Fake methods are
  the subject, never the source of expected values. Rejection, transport failure and cancellation tests must assert
  precisely which state committed and which did not. Use injected clock/gates and coroutine test control; no sleeps.
- At the end of each subtask, report changed files, focused test names/counts, exact commands and results, uncertain
  contract points, and stop. Coordinator reviews the full changed/new diff, runs the focused tests independently, asks
  for corrections in that same session, then records approval before another session is started.

## 018a — original-result replay and push failure controls

**Allowed implementation:** shared-test `FakeSyncService.kt` and `FakeAccountSyncApi.kt`; new/focused JVM tests and test
fixtures. Keep `pull` and `snapshot` stubbed in this gate.

1. Replace outcome-only recording as dedup authority with a private account-scoped record of mutation ID, **exact
   immutable `requestJson`**, and original accepted batch or original rejection code. Check it before staging a request.
   A same-account same-ID identical-byte retry returns `DUPLICATE` with the original batch or rejection; it performs no
   command, revision, active-pointer or log effect. Changed bytes under that ID return `REJECTED` with
   `mutation_id_reused`, without replacing the original. The ID is scoped to the bound account and cannot reveal another
   account's result. Keep inspection helpers defensively copied and make accepted and rejected outcomes equally durable.
   Existing `FakeSyncServiceTest` calls `recordOutcome`/`replayOutcome` directly; preserve their inspection contract or
   explicitly update those tests while ensuring manually recorded outcomes alone cannot impersonate a validated request
   identity.
2. Execute a multi-request push in submitted order, one atomic command at a time. Earlier commits survive a later
   rejected command or top-level transport failure at the chosen boundary. Preserve existing `requirePushBatch` checks
   for count, duplicate IDs and raw wrapper/envelope consistency; never sort requests or regenerate IDs. Ensure retry of
   a rejected original remains a duplicate rejection even if source state later becomes valid.
3. Add deterministic **test-only** push controls for failure before commit, commit followed by lost response, and
   delayed/gated response. Model which request/commit boundary the control affects. Let coroutine cancellation
   propagate; do not broadly catch it or programming errors as transient service failures. The delayed gate must allow a
   test to prove there is no extra effect after cancellation. Keep failure injection narrow and opt-in, with no Android
   SDK dependency.
4. Tests must include accepted/rejected duplicate replay, changed-payload reuse, identical raw bytes after lost
   response, original batch remaining old after a later winning edit, different-account scope, complete
   old-delete/new-upsert replay, dependent commands in order, no partial command effects, and failure/cancellation state
   snapshots. A failed-before-commit request can be retried as new; an accepted-but-lost response must replay as
   duplicate.

**Coordinator gate:** inspect exact request-byte comparison and atomically stored original result. Run 018a focused
tests and all existing fake command suites. Verify the 223-test baseline has no regressions. Approve/close the same Muse
session before 018b.

## 018b — 90-day retained log and complete-batch pull

**Allowed implementation:** same shared-test fake/adapter and focused JVM tests. Preserve 018a replay. Leave `snapshot`
stubbed until 018c.

1. Give committed account batches an internal commit time from injected `clockMillis`, preserving public
   `SyncBatchModel` shape. Implement deterministic 90-day change-log pruning. **Never prune or rewrite dedup
   identity/original results** when pruning log rows; a lost response retried after more than 90 days must return the
   original batch and must not displace a later winner.
2. Make cursors opaque, account bound, and tied to retained commit boundaries. Do not parse random token text or rely on
   its lexical order. A null cursor reads from the retained beginning; clients with no persisted cursor still require a
   full snapshot to discover older unchanged roots. Unknown/foreign cursor and expired-history cursor fail explicitly
   through typed top-level failures, without silently restarting or disclosing another account's data. Keep a valid
   nonempty boundary cursor even for an empty final page. Seeded `appendChangeBatch` and command `commitBatch` must
   participate in the same read ordering.
3. `pull` returns complete batches in commit order, at most the bounded **batch** limit (1–200). Never split an
   old-active DELETE/new-active UPSERT batch. `hasMore=true` requires a nonempty page and advancing `nextCursor`; an
   empty final page preserves the supplied valid boundary. The returned data must be defensively copied. Add
   deterministic middle/final-page transport failure and cancellation controls without mutating the checkpoint or log.
4. Test exact page batches/cursors across multiple accounts, pagination and empty tail, account isolation,
   invalid/foreign/expired cursors, 90-day boundary, preserved old unchanged root after log expiry, complete replacement
   batches, post-prune old accepted and rejected replay, and page failure/cancellation. Use virtual time or injected
   clock; do not sleep.

**Coordinator gate:** inspect retention boundary and cursor bookkeeping against `sync.md`, OpenAPI and the client
protocol. Check no page splits a commit batch, no stale token resumes at an empty log, and dedup survives pruning. Run
focused tests plus all 018a and existing fake suites. Approve/close the same Muse session before 018c.

## 018c — frozen paginated snapshot and final contract gate

**Allowed implementation:** same shared-test fake/adapter and focused JVM tests. Preserve 018a/b behavior.

1. A null page token starts an account-bound snapshot session that freezes a **complete current owned, live-root view**
   and a single baseline pull cursor. Include workouts, routines and custom dependencies as canonical UPSERT records
   with revisions; exclude tombstones, foreign roots and bundled catalogue. Stable deterministic ordering is useful, but
   correctness must not depend on dependencies appearing before references on a page.
2. Continuation tokens bind the account and frozen snapshot. Later commits cannot change the session's records or
   baseline; those later batches remain available via pull after the baseline. Page size is bounded in **records**
   (1–500). `hasMore=true` requires a nonempty next token; terminal pages have null next token. Unknown, foreign and
   expired tokens fail explicitly through typed top-level failures. Returned records/tokens must not expose mutable
   stored state or other accounts.
3. Add deterministic snapshot middle/final-page failure and cancellation controls. Test a multi-page snapshot where a
   custom dependency is on a later page, concurrent writes/deletes occur between pages, every page carries the same
   baseline, and a subsequent pull from that baseline returns the later commits. Also test empty account, old unchanged
   roots after 90-day log pruning, invalid/foreign/expired tokens, page boundaries, no duplicate/conflicting records,
   failure and cancellation.
4. Remove or update stale fake/adapter KDoc only after behavior is real. Run the complete 018 contract matrix
   from [018-task.md](018-task.md), including all 17 approved mutation commands and original-result replay. The fake
   remains test-only and absent from production APK.

**Final coordinator gate:** independently inspect all changed/new files and the whole 018 contract, then run the Gradle
commands and XML/APK/diff checks in the [handoff](../../coordinator-handoff.md). Record actual counts and limitations in a
new 018 review and update the Android architecture/testing/implementation-plan status. Stop after approval. Do not start
M5 or backend work from this plan.
