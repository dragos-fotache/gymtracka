# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next eligible task and authority

**R07c — shared push-before-pull orchestration and package gates** is the next eligible slice under the owner's
[standing commit/rotation/automatic-continuation rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
A fresh coordinator may prepare its bounded specification and implement it without another next-slice approval.
[R07b bounded HTTP pull](archive/remaining-work/R07b-review.md) and
[R07a checkpoint transactions](archive/remaining-work/R07a-review.md) are accepted on Room v10, following R06 push,
R05 canonical storage/publication and R04 session seams. No implementation specification is currently open.
Bring every unresolved blocker/question back to the user. This authority does not cover activation, pushes, deployment,
private configuration or live Google/account/personal-device actions.

Reuse [bounded pull](sync/implementation.md#bounded-incremental-pull),
[checkpoint transactions](sync/implementation.md#incremental-checkpoint-transactions),
[bounded push](sync/implementation.md#bounded-push-delivery),
[atomic acknowledgment](sync/implementation.md#atomic-canonical-acknowledgment),
[fenced canonical batches](sync/implementation.md#fenced-canonical-batches) and
[protected publication](sync/implementation.md#protected-publication), following the
[approved push/pull policy](sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull).

R07c must own **one uninterrupted admitted run** under the required shared `PushRunOwner` per physical DB. Pair API/store
with the same authorized service/account/session. `PullDeliveryLoop(api).pull(run, maxPages, pageLimit)` already accepts
an admitted `PushRun`; it creates no admission/mutex/owner. Existing push driver currently admits its own `withRun`, so
settle a minimal compatible same-run composition seam before delegation, preserving its standalone API/semantics.
Do not nest driver admission or release/reacquire ownership between push and pull.

Push before pull; retained rejection/corruption/oversize/deferred/auth/failure stops must not be bypassed. Push
`LimitReached` never probes another head; `Empty` means only fenced outbox emptiness. Push acknowledgment cursors never
advance the pull checkpoint or last-success. New local capture during network remains protected. Define valid-tail/
last-success integration explicitly and transactionally under complete checkpoint/session/epoch fences, separately
from outstanding intent, projection publication and convergence. R07b `Tail` is transport exhaustion at a fenced boundary,
not full success/convergence; budget stops, recovery needs and failures cannot claim a valid tail.

Missing complete baseline/cursor or explicit `CursorExpired` requires R08, never null-cursor history bootstrap.
Invalid/foreign/wrong-context tokens retain failures without implicit cleanup. R07a read/write attempts retire earlier
tokens; successful commits chain fresh tokens, failed/uncertain commits require durable reread. R07b deeply owns/validates
whole pages before post-response suspension and commits each complete ordered batch with its own boundary, preserving
earlier commits after later failure. Do not parse opaque tokens or rewrite outbox intent. Publication advances account
epoch; eventual callers must continue with its returned epoch. Durable authority/account transitions (R09), complete
bootstrap (R08), live edit/timer barriers (R12a), convergence (R10) and production activation remain separate milestones.
The [roadmap](remaining-work-roadmap.md#status-and-dependencies) owns status/dependencies.

## Capability boundary

The app remains signed-out/local-only in production. Room v10 capture/watermarks, independent canonical storage, fenced
batches, inert protected publication, claiming/outcomes/retry/atomic acknowledgment, bounded HTTP push, incremental
checkpoint transactions and an inert bounded HTTP pull driver exist. Strict codecs, secure HTTP adapters, encrypted
vault, refresh coordination and startup recovery seams also exist. None is constructed/activated by the app.
No shared push/pull engine, valid-tail/last-success integration, complete bootstrap, durable account/Google UI,
durable sign-out flow or background scheduler exists. R09 owns the Room-authority adapter/journal; R12a owns real finite
edit/timer barriers. See [architecture](architecture.md) and [sync implementation](sync/implementation.md).

## Risks and external blockers

- **Legacy accepted auth replay can remain incompatible.** Preserve strict parsing and exact uncertain attempt identity;
  no rounding, replay rewrite, new uncertain key, blanket revocation or inferred cache deletion. See the
  [legacy limitation](sync/implementation.md#legacy-auth-replay-limitation).
- Stored vault phases and process-local leases are not Room authorization or durable sign-out. Recovery must reconcile
  both stores before exposing account data or admitting authenticated work. Checks cannot retract admitted bytes or
  infer that cancellation rolled back a synchronous commit.
- Complete bootstrap, core two-store convergence and finite-edit/timer barriers precede user-facing activation.
- Phone LAN/away HTTPS/private connectivity, Web/server Google audience, installed signing certificate, live
  device/account permission and laptop recovery arrangements remain outstanding. These block R17, not deterministic
  client development.
- Backend access is operational isolated build/start/black-box HTTP only. Never inspect/change backend source/private
  configuration or use personal databases/fixtures. Do not assume a device/backend is running.

## Pickup checklist

1. Inspect both live working trees/indexes/untracked files and HEADs; preserve unrelated changes. Discover agents/models/
   processes; historical pane/cleanup claims are not live facts. Completed Worker/QA conversations require fresh native
   Contributor/high sessions for the next slice.
2. Read task-relevant architecture/design/testing/product/contracts and verify dependencies/standing scope.
3. Prepare one concrete bounded R07c specification with independent allowlists, harness facts, race/cancellation/restart
   obligations, proportional tests/gates and explicit stop before delegation. Escalate unresolved questions/blockers.
4. Serialize every Gradle run through explicit slots. At acceptance update owning docs/archive evidence, commit only
   scoped changes locally, rotate to a fresh coordinator for the next eligible slice and close only your own pane after
   acknowledged transfer. No automatic pull-forward of deferred milestones or activation.

Recorded evidence is indexed in [testing strategy](testing-strategy.md#recorded-verification).
The [R07b review](archive/remaining-work/R07b-review.md) records fresh full offline JVM, compilation and separately
filtered driver/checkpoint Room gates on isolated API 37. **R07c must execute full unfiltered instrumentation and explicit
debug assembly/lint/APK checks** as the R07 package gate; no new full instrumentation claim follows from R07a/b filtered
passes. Latest full Android evidence remains R06d. No new API24/backend/Google/engine-convergence/app-activation pass
is claimed. Required unavailable package gates are blockers, not silently deferred obligations.
