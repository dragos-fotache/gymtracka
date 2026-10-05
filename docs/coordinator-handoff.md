# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next proposed task and authority

**R06d — bounded durable push delivery loop** is the next proposed slice and requires specific owner authorization before
planning or implementation. [R06c atomic canonical acknowledgment](archive/remaining-work/R06c-review.md), R06a/b,
R04a/b/c session foundations and R05a/b/c canonical storage/publication are accepted. The single-head claiming,
outcome/retry and atomic acknowledgment seams now exist; no HTTP driver/delivery loop exists. R06c acceptance does not
authorize another specification, production construction/app activation or delivery operation.

Reuse [current capture](sync/implementation.md#room-and-local-capture),
[durable head claiming/replay admission](sync/implementation.md#durable-head-claiming-and-replay-admission),
[single-head outcomes/retries](sync/implementation.md#single-head-push-outcomes-and-retryerror-handling),
[atomic canonical acknowledgment](sync/implementation.md#atomic-canonical-acknowledgment),
[fenced canonical batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication), and the approved
[push/outcome design](sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull).
Share one required run owner per physical database. Accepted snapshots bind account/exact durable identity but remain
insufficient write authority: acknowledgment rechecks live admission, exact head, session/epoch and affected generations
atomically. Write attempts conservatively consume callback tokens even on rollback/uncertain commit; recovery reclaims
unchanged frozen work, never interprets token consumption as persistence evidence. Canonical acknowledgment never
advances the pull checkpoint or last-success and preserves newer intent. Publication advances the account epoch;
future callers must follow its returned epoch rather than reuse stale fences. The
[roadmap](remaining-work-roadmap.md#status-and-dependencies) owns complete status/dependencies.

## Capability boundary

The app remains signed-out/local-only in production. Room v10 persistence, scoped mutation capture with independent
account generation watermarks/canonical storage, fenced canonical batches, an inert complete-graph protected publisher
with a required injected lease, inert claiming/outcomes/retry/atomic acknowledgment, strict wire codecs, secure sync/auth
HTTP adapters, encrypted vault, refresh coordination and inert startup recovery seams exist. The required durable
Room-authority adapter/journal remains R09, and the real finite-edit/timer barrier remains R12a. HTTP/session/publication/
push components are not constructed by the app. There is no running sync engine, activated remote publication, working
account/Google UI, durable sign-out flow or background scheduler. See [architecture](architecture.md) and
[sync implementation](sync/implementation.md).

## Risks and external blockers

- **Legacy accepted auth replay can remain incompatible.** Preserve strict parsing and exact uncertain attempt identity;
  no rounding, replay rewrite, new uncertain key, blanket revocation, or inferred cache deletion. Details and authority:
  [legacy limitation](sync/implementation.md#legacy-auth-replay-limitation).
- A stored vault phase or process-local refresh lease is not Room authorization. Invalidation is not durable sign-out.
  Recovery must reconcile both stores before exposing account data or admitting authenticated work.
- Complete bootstrap, core two-store convergence, and finite-edit/timer barriers are prerequisites for user-facing
  activation, not optional post-activation cleanup.
- Phone access must work on LAN and away. HTTPS/private connectivity is not configured; Google Android debug client
  exists, but Web/server audience and installed signing certificate are unconfirmed. Live device/account permission and
  laptop recovery arrangements are also outstanding. These block R17 live gates, not deterministic client development.
- Backend access is operational build/start and isolated black-box HTTP only. Personal configuration/databases are not
  test inputs. Do not inspect/change backend source or run its suite as part of Android work.

## Pickup checklist

1. Inspect both working trees, including untracked files, and live HEADs. Never trust archived clean/dirty or pane claims.
2. Confirm this snapshot against the owner's current instruction and the roadmap; preserve unrelated work.
3. Read task-relevant architecture, design, tests, product behavior, and the required shared contract documents.
4. Prepare one bounded specification with dependencies, concrete interfaces, independent tests, and an explicit stop.
5. Before delegation, apply the current workflow and rediscover live agents. No archived session identity is reusable by
   assumption. Standing Herdr coordination permission is in the workflow; no automatic continuation, commit/push,
   private configuration, or deployment is authorized.

Recorded evidence and limitations are indexed in [testing strategy](testing-strategy.md#recorded-verification).
Latest scoped client evidence is the [R06c review](archive/remaining-work/R06c-review.md): fresh full offline JVM and
focused isolated API 37 acknowledgment plus push/canonical regressions. Full unfiltered instrumentation/build/lint
remain prior R06a evidence, with broader gates deferred to R06 package completion after the delivery loop. No new
API 24, backend, Google, engine-convergence or app-activation pass is claimed.
