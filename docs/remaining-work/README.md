# Active task specifications

**No implementation task is currently open here.**
[R06d bounded durable push delivery](../archive/remaining-work/R06d-review.md), R06a/b/c and R05a/b/c are accepted.
R07 incremental pull is the next proposed package and needs specific owner authorization plus a bounded pickup
specification. See [current pickup](../coordinator-handoff.md) and [the roadmap](../remaining-work-roadmap.md).
Production remains signed-out/local-only; accepted inert components do not authorize app activation.

Create `RNN-task.md` (or subtask equivalent) only when that planning slice is authorized. Include the concrete contract,
allowlists, independent acceptance tests, proportional gates and stop condition from
[coordinator workflow](../coordinator-workflow.md). An in-progress review may live beside its task until accepted;
do not label unresolved/failing work complete.

At closure, extract enduring implementation facts, approved design refinements and unresolved obligations into their
[owning documents](../README.md#single-sources-of-truth), then move the completed spec/review into `docs/archive/remaining-work/`.
Update links and the archive index. Partial parent packages stay in the roadmap with outstanding slices; do not
keep a completed split plan here merely because a deferred slice remains.

**Replace current state; append only to historical evidence.** Old completed records are in the
[archive](../archive/README.md), not prerequisite reading for each new assignment.
