# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next proposed task and authority

**R06b — push outcome/retry handling** is the next proposed slice and requires specific owner authorization before
planning or implementation. [R06a claiming/replay](archive/remaining-work/R06a-review.md), R04a/b/c session foundations
and R05a/b/c canonical storage/publication are accepted. R06a adds inert serialized claiming/replay admission only;
outcome application/acknowledgment, later R06 slices, production engine construction and app activation remain outstanding.
No next-slice specification or delivery operation is authorized by R06a acceptance.

Reuse [current capture](sync/implementation.md#room-and-local-capture),
[durable head claiming/replay admission](sync/implementation.md#durable-head-claiming-and-replay-admission),
[fenced canonical batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication), and the approved
[push/outcome design](sync/integration-design.md#4-exclusive-engine-ownership-push-and-pull).
Publication advances the account epoch; future callers must follow the returned epoch rather than reuse stale fences.
Share one required run owner per physical database. A prior successful claim check is not result-write authority:
canonical outcomes and exact acknowledgments must recheck the live admission and durable fences atomically with writes.
The [roadmap](remaining-work-roadmap.md#status-and-dependencies) owns complete task status and dependencies.

## Capability boundary

The app remains signed-out/local-only in production. Room v10 persistence, scoped mutation capture with independent
account generation watermarks/canonical storage, fenced canonical ledger batches, an inert complete-graph protected
publisher with a required injected lease, inert durable head claiming/replay admission, strict wire codecs, secure
sync/auth HTTP adapters, encrypted vault, refresh
coordination and inert startup recovery seams exist. The required durable Room-authority adapter/journal remains R09,
and the real finite-edit/timer barrier remains R12a. HTTP/session/publication components are not constructed by the app.
There is no running sync engine, activated remote publication, working account/Google UI, durable sign-out flow or
background scheduler. See [architecture](architecture.md) and [sync implementation](sync/implementation.md).

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
   assumption. No automatic continuation, commit/push, private configuration, or deployment is authorized.

Recorded test evidence and its limitations are summarized in [testing strategy](testing-strategy.md#recorded-verification).
Latest scoped client evidence is the [R06a review](archive/remaining-work/R06a-review.md): fresh full JVM/build/lint and
unfiltered isolated API 37 instrumentation, including independent claiming/replay tests. It adds no API 24, new backend,
Google, engine-convergence or app-activation pass.
