> **Historical record for the retired simulator. Do not resume.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# Assignment 017 — M4c fake semantic commands

Only begin after explicit approval of Assignment 016. Implement ONLY the ten remaining semantic command handlers and
independent fake tests; then STOP. Test-only shared fake, no engine/Room/HTTP/auth/UI/worker/schema/dependencies,
commits, or personal skill/memory/config edits.

Read the full backend domain and client protocol. All source reads and validation use CURRENT service state and caller
ownership; known revisions, client clocks, and client-materialized copies never override eligibility. Stage every effect
before committing revisions/log/roots; a rejected command changes nothing. Preserve the independently tested basic
handlers.

- workout.finish: existing live owned active root, at least one valid completed set; remove unfinished sets, remove
  empty exercises, renumber both levels, preserve survivor IDs/inputs/configured and recorded rest, then set
  status/time. Reject an already finished/deleted source or invalid time/shape. The payload contains only finishedAt,
  not an uploaded finished record.
- workout.performAgain: current finished owned source; validate complete childIds bindings against source IDs, not list
  index alone. Create fresh active root with exact supplied IDs/time and source name/notes/rest/order/weight/reps; all
  completion false/recorded rest null. Atomically delete prior active and upsert new; collision/partial/reused plans
  roll back replacement.
- routine.startWorkout: current eligible saved owned routine with nonblank name and at least one exercise; same
  binding/collision/replacement rules, template inputs/rest copied, default workout notes, incomplete sets/null rest.
  Draft or currently invalid saved autosave source rejects.
- workout.saveAsRoutine: current finished owned workout and valid supplied nonblank name; complete fresh binding plan,
  saved routine template from source, no completion/recorded rest. Never changes source or active workout. Preserve
  configured rest/order/inputs and exact supplied IDs.
- workout.addExercises: owned live active target, caller-visible exercises, complete stable additions plan. Select
  deterministic most recent finished history per exercise by finishedAt DESC, workout UUID DESC, then workout-exercise
  position ASC. Copy configured rest and completed inputs from the SAME selected occurrence. Use exactly one empty
  set/default 30 seconds when unavailable. Supplied setIds count must match current derived shape; otherwise
  history_shape_changed with zero effects. Repeated catalogue references allowed with distinct entry/set IDs. Reject
  target/child collisions before changing anything.
- workout.replaceExercise: locate owned target child in current active root and validate new exercise visibility;
  preserve every other root/child field/ID including recorded rest. No reparenting.
  Missing/wrong-parent/deleted/finished targets reject.
- workout.deleteExercise / workout.deleteSet: locate child under envelope root, remove it and descendants, renumber
  current siblings atomically; no stale position assumptions. Missing/wrong-parent/finished roots reject.
- routine.deleteExercise / routine.deleteSet: same ownership/child lookup/current sibling rules; draft or saved routine
  may become empty through autosave semantics. Never demote saved state.

Independent expected-record tests for every command; unfinished/interleaved finish cleanup at both levels, immutable
source copy, stable bindings and rejected partial/collision plans, old-delete/new-active batch, source changed/deleted
since capture, history tie/duplicate occurrence/rest association and history_shape_changed rollback, replacement
preserving all fields, child delete order/ownership, and rejection preserving revisions/log/roots. Do not infer expected
values by invoking the handler under test. Expose deterministic fake inspection only where needed for assertions.

Run narrow then full JVM tests, Kotlin compilation/assembly/lint, androidTest source compilation and both diff checks;
report actual counts and STOP. Deduplication/replay, retention, paging and snapshot completion remain Assignment 018.
