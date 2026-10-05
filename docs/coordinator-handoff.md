# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next eligible task and authority

**R08a — staged bootstrap storage foundations** is next dependency-ready under the owner's
[standing commit/rotation/automatic-continuation rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
A fresh coordinator may prepare its bounded specification and implement it without another next-slice approval.
[R07c](archive/remaining-work/R07c-review.md) accepts uninterrupted push-first/valid-tail orchestration and full R07
package gates after accepted R07a/b, R06, R05 and R04. No implementation specification is currently open.
Bring every unresolved blocker/question to the user; no activation, push, deployment, private configuration or live
Google/account/personal-device action is authorized by this continuation.

R08a should settle the smallest durable staging/schema and inert storage boundary for a complete frozen account snapshot
outside visible data, with a bounded normative specification before delegation. Verify the live Room v10/schema/code
before assigning a migration version. Specify exact account/session/epoch/stage/limit/baseline binding, whole-record
ownership/validation, duplicate-root/progress evidence, incomplete-stage restart/abandonment and cancellation/fencing,
without publishing partial canonical/domain data or touching outbox/newer intent/other scopes. No permanent domain-size
cap or truncation. A complete snapshot must eventually find unchanged old roots and a valid empty account, including
references on later pages; incremental null-cursor history is not bootstrap. Storage foundations alone do not complete
R08: bounded download validation and complete baseline/publication recovery remain dependent slices, specified on pickup.

Reuse [canonical storage/batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication),
[shared push-first ownership/valid tail](sync/implementation.md#shared-push-before-pull-and-valid-tail),
[checkpoint transactions](sync/implementation.md#incremental-checkpoint-transactions),
[bounded pull](sync/implementation.md#bounded-incremental-pull), existing snapshot models/codecs/API/refresh, and
[approved snapshot design](sync/integration-design.md#5-frozen-snapshots-and-full-resync). Consult the task-relevant
[shared contract](../shared-docs/docs/backend-contract/README.md) sections and referenced wire types, especially frozen
snapshot/token/retention semantics; do not ingest the entire contract map at pickup.
Do not recreate accepted seams or treat an existing baseline flag as authority to fabricate a complete snapshot.

One explicit application-owned `PushRunOwner` per physical DB remains required across every future caller, including
network intervals. Pair API/store to the same authorized service/account/session. Push stops never authorize pull/snapshot
around retained rejection/corruption/oversize/deferred/auth/failure; budget limits never probe heads. Frozen requests and
original uncertain identity survive recovery. Earlier committed acknowledgments/batches survive later failure; failed/
uncertain writes require durable reread, never token-retirement inference. Opaque cursors/tokens are not interpreted.

`lastSuccess` is fenced validated-tail observation, not account convergence or publication. New capture during network
remains protected and outstanding intent is separate. Existing inert publication advances account epoch; eventual callers
must continue with its returned epoch. Durable authority/account transitions (R09), real finite edit/timer barriers
(R12a), convergence (R10), activation/UI and scheduler remain separate. Full snapshot absence/baseline establishment
belongs to complete R08 publication, never a partial stage or ordinary incremental absence.

## Capability boundary

The app remains signed-out/local-only in production. Room v10 capture/watermarks, independent canonical storage,
fenced batches, inert protected publication, durable claiming/outcomes/retry/atomic acknowledgment, bounded push/pull,
checkpoint transactions and shared push-first/valid-tail driver exist. Strict codecs, secure HTTP adapters, encrypted
vault, refresh coordination and startup recovery seams also exist. None is constructed/activated by the app.
No staged/complete bootstrap, durable Room-authority adapter/journal, account/Google UI, sign-out orchestration,
two-store engine convergence, live edit/timer publication barrier or background scheduler exists.
See [architecture](architecture.md) and [sync implementation](sync/implementation.md).

## Risks and external blockers

- **Legacy accepted auth replay can remain incompatible.** Preserve strict parsing and exact uncertain attempt identity;
  no rounding, replay rewrite, new uncertain key, blanket revocation or inferred cache deletion. See the
  [legacy limitation](sync/implementation.md#legacy-auth-replay-limitation).
- Stored vault phases/process-local leases are not Room authorization or durable sign-out. Recovery must reconcile both
  stores before exposing account data/admitting authenticated work. Checks cannot retract admitted bytes or infer that
  cancellation rolled back a synchronous commit.
- Complete bootstrap, core two-store convergence and finite-edit/timer barriers precede user-facing activation.
- Phone LAN/away HTTPS/private connectivity, Web/server Google audience, installed signing certificate, live device/account
  permission and laptop recovery arrangements remain outstanding. These block R17, not deterministic R08a development.
- Backend access is operational isolated build/start/black-box HTTP only, if a gate requires it. Never inspect/change
  backend source/private configuration or use personal databases/fixtures. Do not assume a device/backend is running.

## Pickup checklist

1. Inspect both live working trees/indexes/untracked files and HEADs; preserve unrelated changes. Discover actual agents,
   models/processes. Completed Worker/QA conversations require fresh native Contributor/high sessions for R08a.
2. Follow [progressive reading](README.md#progressive-reading-and-context-discipline): report readiness from scope,
   dependencies/gates and live-state checks, not a full design audit. After release read the concrete authoritative
   architecture/design/testing/product/contract sections and source/harness seams needed for each R08a decision.
3. Prepare one bounded R08a specification with concrete schema/storage/harness facts, independent allowlists,
   safety/race/cancellation/restart obligations, proportional gates and exact stop before delegation.
4. Every entity change requires a data-preserving migration/KSP/schema export and all supported starting versions plus
   copied real v1 asset coverage; no destructive fallback. Declare required package milestones explicitly.
5. Serialize every Gradle run through explicit slots. At acceptance update owning docs/archive evidence, commit only
   scoped changes locally, rotate to a fresh coordinator for the next eligible slice and close only your own pane after
   acknowledged transfer. No deferred milestone or activation pull-forward.

The [R07c review](archive/remaining-work/R07c-review.md) records fresh full unfiltered instrumentation and explicit
assembly/lint/APK gates, plus fresh offline JVM/compilation; [testing strategy](testing-strategy.md#recorded-verification)
owns the evidence index. No required R07 package gate remains deferred. These are not new API24/backend/Google/
two-store convergence/activated-app claims. Required unavailable R08a gates/models remain blockers, never skipped or
silently substituted. Rediscover/provision only test-owned resources; historical cleanup claims are not live ownership.
