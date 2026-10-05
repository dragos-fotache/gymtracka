# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow [progressive reading](README.md#progressive-reading-and-context-discipline) and the
> [coordinator workflow](coordinator-workflow.md).

## Next eligible task and authority

**R08b — bounded frozen-snapshot download validation** is next dependency-ready under the owner's
[standing local-commit/rotation/continuation rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
A fresh coordinator first verifies minimal pickup readiness, reports it asynchronously to the outgoing live coordinator
and remains read-only until ownership release. After release it may settle one bounded normative specification and
implement R08b without another next-slice approval. Readiness is not a completed design audit; expand concrete source/
contract/harness reading after release, before specification/delegation. Bring every unresolved blocker/question to the
user. No activation, push, deployment, private configuration or live Google/account/personal-device action is authorized.

[R08a](archive/remaining-work/R08a-review.md) accepts durable inert staging storage and its Room10→11 migration after
accepted R07a–c/R06/R05/R04. No implementation specification is currently open. R08b should compose actual snapshot
HTTP download on the SAME admitted `PushRun`, using its begin/read/check/append/abandon storage and existing
`GymSyncApi.snapshot`, snapshot models/codecs, secure HTTP and refresh decoration. Settle concrete driver/budget/result/
harness facts on pickup; do not recreate storage/owners or speculate on complete publication APIs.

Reuse [staging storage](sync/implementation.md#frozen-snapshot-staging-storage),
[shared push-first ownership](sync/implementation.md#shared-push-before-pull-and-valid-tail),
[canonical batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication) and
[snapshot design](sync/integration-design.md#5-frozen-snapshots-and-full-resync).
Consult task-relevant shared frozen snapshot/token/retention, typed failure and wire-type requirements, not the entire
[contract map](../shared-docs/docs/backend-contract/README.md). Existing continuation validation now requires nonempty,
not nonblank, and preserves whitespace exactly; no token interpretation/normalization or permanent content/history cap.

R08a stores one account/stage with immutable origin session/epoch/fixed limit, original full checkpoint/baseline
observation, pinned frozen baseline and complete records/continuation/page evidence. Whole typed pages are deep-owned
before suspension; strict retained validation rejects corruption/duplicate roots/cycles/missing nonterminal progress.
A stage begun/chained in a live admitted run can continue there. Durable read/restart yields ONLY a nonappendable
observation for explicit exact-stage abandonment and NEW-stage/null-token restart; no cross-run resume or implicit
cleanup. Work budgets must not become a permanent account-size cap or silently truncate complete records.
Selected stages, including downloaded/corrupt origin metadata, block ordinary push/pull until explicitly completed or
abandoned. Foreign scopes are unaffected. Begin requires ANY selected-account outbox presence=false; local capture after
begin remains allowed and unchanged. Downloaded storage is NOT actual HTTP exhaustion, a complete validated graph,
canonical baseline/absence, last-success, convergence or visible publication.

R08b must fence actual data, terminal pages and failures/cancellation alike under uninterrupted shared ownership, retain
exact token/limit/baseline context and earlier committed pages, and distinguish explicit snapshot expiry from invalid/
foreign/wrong-context tokens. Invalid tokens never imply bootstrap/cleanup. Cancellation/uncertain commits require
honest durable reread, not retired-token success inference. Preserve newer intent, exact frozen uncertain request IDs/
bytes, domain/outbox/watermarks/canonical checkpoint/last-success/other scopes/catalogue. Pair API/store to the same
authorized service/account/session and explicitly share ONE application-owned `PushRunOwner` per physical DB across
network intervals. Only fenced push Empty authorizes following download; retained/rejected/corrupt/oversize/deferred/
auth/failure/budget stops never authorize recovery around intent. Limits never probe heads; push cursors are not checkpoints.

**R08c complete canonical baseline/absence/publication recovery** is separate and dependent on R08b. Only a complete
validated frozen account snapshot can establish absence/baseline, including unchanged old roots and a valid empty account
with later-page dependencies. Never infer deletion from ordinary incremental/staged absence or publish partial data.
Existing protected publication advances epoch; eventual callers must use its returned epoch. R09 durable Room/vault
journal/authority, R12a real finite-edit/timer barriers and R10 two-store convergence still precede app activation.

## Capability and verification boundary

Production remains signed-out/local-only. Room11 retains prior catalogue/domain/capture/canonical/outbox/checkpoint
storage and adds independent staging headers/records/tokens; schemas1–11 are preserved, bundled source asset stays v1.
No complete bootstrap, bounded snapshot HTTP driver, durable Room-authority adapter/journal, Google/account UI, transition
or engine convergence, live edit/timer publication barrier or scheduler is constructed by the app.
See [architecture](architecture.md) and [implemented sync components](sync/implementation.md).

R08a's review records fresh full offline JVM, full unfiltered instrumentation, every-supported-start1–10→11 plus copied
real-v1/populated-v10 migrations, production/KSP/Android-test compilation, assembly/lint and targeted APK preservation.
No required R08a gate was substituted/deferred. These are not API24/hostedCI/backend/Google/two-store convergence or
activated-app evidence. Required R08b gates are selected proportionately BEFORE delegation; unavailable gates/tools/
models remain user blockers. Do not assume an emulator/backend is running or inherit historical resource ownership.

## Risks, boundaries and pickup checklist

- Strict [legacy accepted-auth replay](sync/implementation.md#legacy-auth-replay-limitation) remains unresolved: preserve
  original uncertain key/body; no rounding/rewrite/new key, blanket revocation or inferred credential/cache deletion.
- Stored vault phases/process-local leases are not durable Room authorization or sign-out. Admission cannot recall
  network bytes or prove cancellation rolled back synchronous storage commits.
- Phone LAN/away HTTPS/private connectivity, OAuth audience/signing and authorized device/account inputs remain live
  milestone blockers, not deterministic R08b blockers. Backend access is isolated operational build/start/black-box HTTP
  ONLY if a declared gate requires it; never backend source/private configuration/personal fixtures/suite.
- Verify BOTH live trees/indexes/untracked/HEADs and actual roles/models/resource ownership. Preserve subsequent user
  changes and accepted commits. Completed R08a Worker/QA conversations require fresh native
  `meta/muse-spark-1.3-contributor`/high sessions, never reuse across slices.
- Use supplied unchanged guide/context and minimal reading route for readiness. After release, settle ONE normative
  R08b spec with concrete interfaces/harness, independent read/write allowlists, safety/race/restart obligations,
  proportional tests/gates and exact stop BEFORE delegation. Reports are fully async, never reply waits/polling.
- Serialize EVERY Gradle command through explicit sole slots. Any further entity change needs data-preserving migration,
  KSP/export/all-supported-start/copied-v1 coverage; verify live version before assigning it, never destructive fallback.
- At accepted gates update owning docs/archive evidence/spec, commit ONLY accepted scoped changes locally (no push),
  start a fresh exact-model coordinator, obtain asynchronous readiness, transfer live role and close ONLY your own pane
  LAST. Do not pull R08c/R09/R10/R12/activation forward in R08b.
