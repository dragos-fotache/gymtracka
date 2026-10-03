# Assignment 016 implementation and review plan

Historical Assignment 016 instructions, preserved after implementation and approval. References to
`/tmp/gymtracka-sync-coordination/` describe the original session; see [the durable review](016-review.md)
and [current handoff](../coordinator-handoff.md) for its completed status.

User explicitly authorized Assignment 016 on 2026-09-27 and asked the current Muse Spark contributor to implement it.
Assignment 015 was coordinator-approved. Implement this slice, report evidence, and STOP for review. Do not begin
017/018.

## Authority and permitted changes

Read `/tmp/gymtracka-sync-coordination/016-task.md` completely; it is the bounded specification. Read repository
AGENTS.md, shared-docs product-behavior, local docs architecture, testing-strategy, sync-implementation-plan handoff/M4,
and shared backend-contract README with all linked contract files including domain-rules, schema, sync, android-client-protocol,
authentication, OpenAPI and fixture. The contract, not your fake's own behavior, determines expected results. Flag
unresolved contradictions before making a contract change.

Permitted implementation: `app/src/sharedTest/java/com/dragosfotache/gymtracka/testing/service/` and focused JVM
tests/helpers under `app/src/test/java/com/dragosfotache/gymtracka/data/sync/`. Preserve prior scaffold tests unless
their explicit stubs must change to the now-implemented behavior. Add small focused shared helper files if needed.
Coordinator owns documentation/status edits after review. No production source, Room, UI, engine, auth, workers,
migrations, schemas, dependencies, build configuration, commits/pushes or global/personal configuration/skills changes.
Do not start other agents or touch other panes. Existing work is uncommitted; compare against
`/tmp/gymtracka-sync-coordination/016-baseline/` to isolate this assignment.

## Step 1: audit and design

Inspect approved typed inputs, canonical records, durable requests, raw-request validation and failure/outcome models.
Retain all 17 kinds; implement only the seven named below. Plan a staged transaction representation containing complete
affected canonical roots, tombstones, child ownership and per-root revisions. Account-bound adapter is sole source of
owner identity. Root identity is `(aggregateType, UUID)` globally across accounts. Child IDs must be validated against
existing ownership (including exercise-parent ownership of sets), not just unique within the incoming payload. Keep
collision evidence after deletion so old IDs cannot be silently reparented/reused.

Explicitly distinguish seeded bundled exercise, muscle and equipment reference namespaces; a muscle/equipment UUID must
not become a valid exercise merely because it is seeded. Bundled catalogue is read-only. Existing scaffold seed API may
be extended compatibly. Canonical names must resolve from current seeded catalogue or current caller-owned custom roots;
upload inputs do not carry authoritative display names.

## Step 2: staged execution and adapter

Validate entire push envelope using the approved raw-request validator before any effects. Stage each individual
mutation fully before publishing effects. A rejected mutation must leave roots/children/ownership/revision
counters/change logs untouched. Return explicit permanent rejected outcomes for domain/lookup errors; unsupported ten
kinds must be explicitly rejected. Do not catch arbitrary programmer/provider failures and turn them into accepted
outcomes. Multi-mutation pushes execute in request order, each mutation atomically; no implementation of dedup replay
yet.

Assign increasing server revisions on commit, independent of client timestamps, generation and known revision. Prefer
per-root counters for command effects; preserve existing scaffold test APIs without mistaking their account counter for
a root revision. Build and validate the complete canonical batch before publishing state. One sole-active replacement
batch contains old delete then new upsert. Use injected UUID/clock deterministically; do not overwrite user/business
timestamps with server time.

Expose narrowly scoped account-safe inspection helpers for independent tests of records/tombstones/revisions/logs; never
return another owner's data through command responses. Ensure committed records/log batches do not retain mutable
request collection aliases. Pull/snapshot remain explicit unsupported stubs. Dedup identity/original results and
retention are Assignment 018; do not claim stable retry semantics complete.

## Step 3: seven basic kinds

1. `workout.start`: create exactly empty ACTIVE root with supplied identity/name/start; reject any target root collision
   (same owner, other owner, tombstone); stage old sole active deletion and new upsert atomically.
2. `workout.upsert`: existing owned live ACTIVE only; complete replacement preserving accepted stable child IDs; reject
   missing/finished/deleted/foreign source, invalid references, child collision or reparenting. Resolve current names,
   preserve notes, configured rest, recorded rest, nullable inputs and full consecutive order. Ignore known revision as
   a precondition.
3. `routine.upsert`: create draft only; existing live owned draft/saved autosave; saved cannot demote, but saved blank
   name/empty children autosave is valid. Complete relationship/child replacement with identity/visibility checks.
4. `routine.save`: owned live source; require nonblank name and at least one exercise, clear draft. Reject
   invalid/missing/foreign/deleted without changes.
5. `customExercise.upsert`: own mutable live custom creation/update only; reject bundled UUID, other owner or tombstone.
   Resolve real muscle/equipment references and validate current first-release primary/secondary/equipment restrictions.
   Replace relationships and preserve optional category/instructions/difficulty/force/mechanics/quantity exactly,
   including nulls.
6. `aggregate.import`: create-only all three aggregate types with complete supplied root/child IDs/domain state. Reject
   any collision/reference/ownership error. Finished workout import requires completed survivors/nonempty
   exercises/valid finished time. Active import performs sole-active replacement. Routine import preserves saved or
   draft snapshot, including saved blank/empty states; ordinary routine create remains draft-only. Custom dependencies
   must already exist; never accept caller-supplied canonical revision/names.
7. `aggregate.delete`: owned known root; live custom referenced by any retained workout/routine rejects (including
   drafts and finished history); remove live children, retain root collision/tombstone/revision evidence, emit deletion.
   Re-delete owned tombstone may emit new deletion revision/batch. Missing/foreign roots reject with safe non-disclosing
   failure. Deleted parents no longer block custom delete.

## Step 4: independent tests

Expected records and batches must be constructed independently from specification, not generated by the same helper
under test or only checked by codec round-trip. Use deterministic providers and coroutine tests, no sleeps/Room mocks.

Cover each successful and rejected kind; full ordered multi-exercise/multi-set replacement, nulls/metadata/current
canonical names, supported custom relationships, source eligibility and immutable finished history, bundled edits,
account A/B isolation, type-scoped roots, child collision across roots and within a set's parent, create-only and
tombstone import collisions, saved autosave/promotion/demotion/import differences, custom reference delete restrictions,
sole-active replacement ordering and rollback. Snapshot all observable state before rejection and assert exact unchanged
records/ownership/revisions/logs, then verify next valid revision has no rejected gap. Test later server commit wins
with deliberately reversed client clocks and known revisions. Check all ten unsupported kinds explicitly reject without
effects, and pull/snapshot still throw. Verify caller mutation of collection inputs or returned inspection values cannot
mutate stored canonical batches/state.

## Step 5: verification and report

From repository root run narrow fake test classes first, then full `:app:testDebugUnitTest`, `:app:compileDebugKotlin`,
`:app:assembleDebug`, `:app:lintDebug`, and `:app:compileDebugAndroidTestKotlin`, using
`--no-configuration-cache --console=plain`. Run unpiped Gradle so failures are visible. Inspect actual executed XML
testcases/counts/failures/errors/skips rather than relying only on BUILD SUCCESSFUL. Run `git diff --check` and
`git -C shared-docs diff --check`. Baseline missing-index KSP warnings and newer-JSON lint warning are known nonfatal
findings. No device run is required for this test-only slice unless evidence demands it.

Report changed files, design decisions, actual checks/counts, remaining unsupported behavior and any uncertainties in
your completion response. Stop and wait for coordinator review; review corrections stay in this assignment/session. Do
not auto-advance or exit before review approval.
