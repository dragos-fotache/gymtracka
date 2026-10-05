# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next eligible task and authority

**R07b — bounded incremental HTTP pull** is the next eligible slice under the owner's
[standing commit/rotation/automatic-continuation rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
A fresh coordinator may prepare its bounded specification and implement it without another next-slice approval.
[R07a checkpoint transactions](archive/remaining-work/R07a-review.md) are accepted on Room v10, following accepted R06
push delivery, R05 canonical storage/publication and R04 session seams. R07c will own shared push-before-pull orchestration
and the package gates. No implementation specification is currently open; acceptance evidence alone is not authority
for activation, pushes, deployment or live actions. Bring unresolved blockers/questions back to the user.

Reuse the [incremental checkpoint seam](sync/implementation.md#incremental-checkpoint-transactions),
[bounded push driver](sync/implementation.md#bounded-push-delivery),
[atomic canonical acknowledgment](sync/implementation.md#atomic-canonical-acknowledgment),
[fenced canonical batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication) and approved
[push/pull policy](sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull).
One required run owner is shared per physical database; caller API/store pairing must bind the same authorized
service/account/session. R07a checkpoint methods live on the existing admitted `PushRun`: do not create a separate pull
owner or nest driver admission. Read once, check before transport and commit complete ordered batches using returned fresh
tokens; failed/uncertain commits require a durable reread. Missing baseline/cursor needs R08, never null-cursor bootstrap.

R07b must validate page/run count/progress and repeated batch IDs/tokens without interpreting opaque values. Explicit
cursor expiry requests R08 recovery; invalid/foreign tokens never silently bootstrap. Empty tail preserves its input
boundary. R07a updates neither last-success nor visible projection. R07c must not pull around retained rejection and
must define tail/last-success separately from outstanding intent/publication. Push LimitReached does not probe another
head; Empty means only fenced outbox emptiness; push acknowledgments never advance pull checkpoint/last-success.
Publication advances the account epoch, so future callers must continue with its returned epoch. The
[roadmap](remaining-work-roadmap.md#status-and-dependencies) owns status/dependencies.

## Capability boundary

The app remains signed-out/local-only in production. Room v10 capture/watermarks, independent canonical storage, fenced
batches, inert protected publication, claiming/outcomes/retry/atomic acknowledgment, bounded HTTP push and inert
incremental checkpoint transactions exist. Strict codecs, secure HTTP adapters, encrypted vault, refresh coordination
and inert startup recovery seams also exist. None is constructed/activated by the app. There is no HTTP pull loop,
shared push/pull engine, complete bootstrap, durable account/Google UI, durable sign-out flow or background scheduler.
The durable Room-authority adapter/journal remains R09; the real finite-edit/timer barrier remains R12a. See
[architecture](architecture.md) and [sync implementation](sync/implementation.md).

## Risks and external blockers

- **Legacy accepted auth replay can remain incompatible.** Preserve strict parsing and exact uncertain attempt identity;
  no rounding, replay rewrite, new uncertain key, blanket revocation or inferred cache deletion. See the
  [legacy limitation](sync/implementation.md#legacy-auth-replay-limitation).
- Stored vault phases and process-local leases are not Room authorization or durable sign-out. Recovery must reconcile
  both stores before exposing account data or admitting authenticated work. Checks cannot retract already-admitted
  bytes or infer that cancellation rolled back a synchronous commit.
- Complete bootstrap, core two-store convergence and finite-edit/timer barriers precede user-facing activation.
- Phone access must work on LAN and away. HTTPS/private connectivity is not configured; Google Android debug client
  exists, but Web/server audience and installed signing certificate are unconfirmed. Live device/account permission and
  laptop recovery arrangements remain outstanding. These block R17 live gates, not deterministic client development.
- Backend access is operational build/start and isolated black-box HTTP only. Personal configuration/databases are not
  test inputs; do not inspect/change backend source or run its suite as Android work.

## Pickup checklist

1. Inspect both working trees, untracked files and live HEADs; preserve unrelated work. Never trust archived tree/pane claims.
2. Confirm the snapshot against the owner's current instruction and roadmap.
3. Read task-relevant architecture, design, tests, product behavior and required shared contracts.
4. Confirm explicit or standing slice authority, then prepare one bounded specification with concrete interfaces,
   independent tests, proportional gates, dependencies/allowlists and an explicit stop. Escalate unresolved blockers/
   questions to the user rather than silently skipping dependencies or widening scope.
5. Before delegation, apply the workflow and rediscover live agents. At acceptance, commit the slice's changes, brief
   a fresh successor for the next eligible slice, verify ownership handoff, and close only your own coordinator pane.
   Standing permission does not cover pushes, private configuration, deployment or live account/device actions.

Recorded evidence is indexed in [testing strategy](testing-strategy.md#recorded-verification).
The [R07a review](archive/remaining-work/R07a-review.md) records fresh full offline JVM, compilation and separately
filtered checkpoint/canonical/acknowledgment Room gates on isolated API 37. Full unfiltered instrumentation and explicit
assembly/lint/APK checks remain R07c's declared package gate; latest full Android evidence remains R06d. No new API24,
backend, Google, engine-convergence or app-activation pass is claimed.
