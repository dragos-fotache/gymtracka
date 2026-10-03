# Assignment 018 — M4d fake durable replay and read protocol

**PAUSED / SUPERSEDED SEQUENCING:** The user approved real-backend-first development. This assignment has not started
and is no longer the next task or a prerequisite for engine work. Preserve it as a historical proposal, not executable
instructions. See
the [current strategy](../sync-implementation-plan.md#approved-strategy-change--real-backend-before-further-simulator-expansion).
Implement server semantics in the real backend; add only narrowly justified client-test doubles. Retain the existing
simulator only temporarily during coverage transfer, then remove it and its obsolete tests under
the [retirement gate](../sync-implementation-plan.md#fake-retirement-gate). Completing this assignment is not required
for retirement. The original specification follows unchanged for reference.

Status on 2026-09-28: Assignment 017 is approved; Assignment 018 has not started.
The [current coordinator handoff](../coordinator-handoff.md) and [018 Muse plan](018-muse-plan.md) give the verified
baseline, file map, and sequential review gates. Preparation of this handoff does not start implementation. Complete
ONLY test fake deduplication, failure controls, pull/snapshot/retention behavior and independent contract tests; then
STOP. No production engine/network/Room/auth/UI/worker/schema/dependency changes, commits or personal config edits.

Use approved typed API/models. The fake must exercise genuine contract behavior rather than echoing requests.
Account-bound service identities are opaque. All 17 command kinds are already implemented and remain staged/atomic.

- Deduplicate by account + mutation ID and exact immutable requestJson. Cache ORIGINAL accepted batch or rejection code.
  Replaying identical bytes returns duplicate outcome with original effective result and no domain/revision/log effects;
  changed payload under same ID rejects mutation_id_reused and does not replace original. Rejected replay retains
  original rejection even if the source later changes. Different-account ID scope follows account ownership and cannot
  reveal another account's result. Outbox wrappers/affected roots are local evidence, never service mutation fields.
- Change-log pruning never erases mutation deduplication/original results. Test an uncertain accepted request retried
  after more than90 days and after a later winning edit: it replays the old original batch without re-execution or
  replacing the current service winner. This follows the client protocol's stable retry lifetime; cursor expiry and
  mutation identity are separate.
- Push ordered requests (maximum 100) in sequence, preserving outcomes in matching order; each mutation is atomic and
  committed log order is authoritative. Reject malformed envelope/request payloads explicitly. Do not sort by aggregate
  type, generate client IDs, or assume submitted client generations are server revisions.
- Deterministic injected service clock/log retention is 90 days. Opaque cursors are account bound;
  unknown/foreign/expired cursors fail with typed permanent/expiry errors. Pull pages contain COMPLETE batches, never
  split a replacement batch, in commit order with bounded batch count. nextCursor denotes covered boundary; hasMore=true
  implies progress/nonempty page. Empty final pages preserve a valid boundary. Cursor expiry recovery is explicit, never
  silently starts from an empty log. Old unchanged roots still exist after their log entries expire.
- Snapshot sessions freeze a consistent complete owned live-root view AND baseline cursor; stable pages are unaffected
  by later commits. Return only complete upsert records with revisions, all custom dependencies/workouts/routines, no
  catalogue/foreign roots/tombstones. Continuation tokens bind to account/snapshot, invalid/expired token is typed
  failure, hasMore requires next token; terminal page has no next token. New commits after baseline remain available
  through pull. Page-size tests must force custom dependencies onto later pages rather than assume page order resolves
  references.
- Explicit deterministic failure controls for transport failure BEFORE commit, accepted service commit with lost
  response, permanent rejection, delayed/gated response, and pull/snapshot middle/final-page failure. Cancellation
  propagates; no sleeps and no Android SDK requirement. Avoid a broad catch that converts programming defects into
  transient failures.

Independent contract tests: accepted/rejected dedup and stable IDs/raw bytes across lost response; reuse mismatch;
ordered dependency create/reference/finish; no partial mutation effects; reversed client clocks with later commit
winner; account cursor isolation; pagination preserves old-delete/new-upsert batch; 90-day boundary and old
unchanged-root snapshot; frozen multi-page snapshot amid concurrent writes with baseline subsequent pull; invalid
tokens/cursors; cancellation/failure controls; all typed outcomes match full original records. Include compile
verification in both JVM and Android test source sets.

Run full JVM plus compilation/assembly/lint/androidTest source compilation and both diff checks, report actual test
counts and residual limitations, then STOP. Coordinator audits fake against contract before any engine test may rely on
it. No M5 authorization is implied.
