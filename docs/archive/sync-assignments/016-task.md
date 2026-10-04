> **Historical record for the retired simulator. Do not resume.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# Assignment 016 — M4b deterministic fake basic semantics

Historical Assignment 016 instructions, preserved after implementation and approval. References to
`/tmp/gymtracka-sync-coordination/` describe the original session; see [the durable review](016-review.md)
and [current handoff](../../coordinator-handoff.md) for its completed status.

Only begin after explicit approval of Assignment 015. Implement ONLY basic fake aggregate commands and independent
tests, then STOP. Fake remains test-only shared sources; no Android/Room/network/engine/auth/UI/worker/schema/dependency
changes or commits/personal config edits.

Use typed approved models and explicit catalogue seeds. Stage each mutation's complete affected records before changing
service state: rejected mutation changes no root/child/revision/log state. Current injected SERVER commit order
increments root revisions and appends complete immutable batches; client timestamps/generations/known revisions never
choose winners. Global root identity is (aggregateType,id) across accounts; owner derives from account-bound adapter,
never payload.

- workout.start creates exactly empty ACTIVE target with input name/time and atomically deletes prior active/upserts
  new. Existing target UUID collision (including deleted root/other owner) rejects. One account has one active.
- workout.upsert replaces complete EXISTING owned nondeleted ACTIVE root, validates all visible exercise
  references/stable child ownership/positions/inputs, ignores knownServerRevision as precondition. Cannot create, mutate
  finished, reparent child from another root, or revive deleted data. Canonical names resolve from actual current
  catalogue/owned customs, revision assigned by service.
- routine.upsert creates draft only, or autosaves existing owned live routine preserving draft/saved state rules; cannot
  demote saved. Blank/empty saved autosave is allowed. routine.save validates nonblank name/exercise count then
  promotes; missing/deleted/foreign roots reject.
- customExercise.upsert creates/updates only mutable owned live custom, replaces relationships and preserves
  metadata/quantity/null instructions exactly. Validate actual global muscle/equipment refs and restrictions.
  Bundled/cross-owner/deleted roots reject, never mutate catalogue.
- aggregate.import creates only and preserves complete input IDs/domain state. Reject collision instead of LWW
  overwrite/remap; verify all references and finished-workout survivor invariants. Active import performs sole-active
  atomic replacement. Routine import preserves an existing local draft/saved snapshot (including valid saved autosave
  blank/empty states); this is creation/import, not promotion by routine.upsert. Custom dependencies must already exist
  or reference global catalogue. Never fabricate revisions/display names in upload input.
- aggregate.delete verifies owned known root; custom still referenced by retained workout/routine rejects atomically.
  Remove children, retain tombstone/revision and emit deletion. A known owned tombstone may accept another delete
  idempotently in effect (emit a new deletion revision/batch); never revive it. An absent/foreign ID returns explicit
  permanent failure without exposing foreign data.
- Reject unsupported semantic kinds explicitly until Assignment 017 implements them. Do not default-accept/echo
  structurally valid input or catch all exceptions as accepted.

Independent fake tests: invalid source state/reference/ownership/bundled edits, immutable finished history, complete
ordered replacement/nulls/metadata, current source display names, UUID/child collision/reparent guards, import
collision/new creation, routines promotion/demotion/autosave states, custom referenced-delete rollback, sole-active
multi-root batch, later valid upsert wins despite deliberately reversed clocks/known revisions, monotonic per-root
revisions and no service changes on rejection. Assertions use independently expected records/changes, not merely codecs
round trips. No Room mocks or sleeps.

Run narrow fake/JVM tests, then full JVM, compile, assembly/lint, androidTest source compilation and both diff checks;
report actual counts/results/remaining unsupported kinds and STOP. Deduplication, pagination and retained snapshots are
separate; do not claim fake complete.
