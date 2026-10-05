# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next proposed task and authority

**R07 — incremental pull** is the next proposed package. Specific owner authorization and a bounded pickup specification
are required before planning or implementation. [R06d bounded push delivery](archive/remaining-work/R06d-review.md)
closes R06 over the accepted claiming, outcome/retry and atomic acknowledgment seams. R04a/b/c session foundations and
R05a/b/c canonical storage/publication are also accepted. R06d acceptance authorizes neither R07 nor production
construction/app activation, commits/pushes or delivery operations.

Reuse the [bounded push driver](sync/implementation.md#bounded-push-delivery),
[atomic canonical acknowledgment](sync/implementation.md#atomic-canonical-acknowledgment),
[fenced canonical batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication) and approved
[push/pull policy](sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull).
One required run owner is shared per physical database, and caller API/store pairing must bind the same authorized
service/account/session. Push stop summaries are not convergence: LimitReached does not probe the next head, Empty
means only fenced outbox emptiness, and push acknowledgments never advance the pull checkpoint or last-success.
Canonical acknowledgment preserves newer intent; publication advances the account epoch, so future callers must use
its returned epoch. The [roadmap](remaining-work-roadmap.md#status-and-dependencies) owns status and dependencies.

## Capability boundary

The app remains signed-out/local-only in production. Room v10 capture/watermarks and independent canonical storage,
fenced batches, inert protected publication, claiming/outcomes/retry/atomic acknowledgment and bounded HTTP push
orchestration exist. Strict codecs, secure HTTP adapters, encrypted vault, refresh coordination and inert startup
recovery seams also exist. None is constructed/activated by the app. There is no incremental pull/checkpoint engine,
complete bootstrap, durable account/Google UI, durable sign-out flow or background scheduler. The required durable
Room-authority adapter/journal remains R09; the real finite-edit/timer barrier remains R12a. See
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
4. After specific authorization, prepare one bounded specification with concrete interfaces, independent tests,
   proportional gates, dependencies/allowlists and an explicit stop.
5. Before delegation, apply the workflow and rediscover live agents. Standing Herdr coordination permission covers
   the authorized slice only; no automatic continuation, commit/push, private configuration or deployment is authorized.

Recorded evidence is indexed in [testing strategy](testing-strategy.md#recorded-verification).
The [R06d review](archive/remaining-work/R06d-review.md) records fresh full offline JVM, final unfiltered isolated API37
instrumentation and R06 package assembly/lint/APK checks. Required R06 Android gates are closed. No new API24, backend,
Google, engine-convergence or app-activation pass is claimed.
