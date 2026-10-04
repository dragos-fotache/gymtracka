# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow the [documentation rules](README.md#documentation-rules) and [coordinator workflow](coordinator-workflow.md).

## Next proposed task and authority

**R04c — startup/session recovery seams** is the next proposed implementation slice. R04a/b vault and refresh machinery
are accepted, but no R04c specification or implementation has started. Obtain specific owner authorization before planning
or implementing it. The current documentation reorganization authorizes no implementation or app activation.

R04c must define recovery/fencing seams and restart/logout-race tests without taking over R09's durable Room transitions.
Use [existing session machinery](sync/implementation.md#encrypted-vault-and-refresh) and
[the two-store design](sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity).
The [roadmap](remaining-work-roadmap.md#status-and-dependencies) owns complete task status and dependencies.

## Capability boundary

The app remains signed-out/local-only in production. Local Room persistence, scoped mutation capture, strict wire codecs,
secure sync/auth HTTP adapters, encrypted vault, and refresh coordination exist. The HTTP/session components are not
constructed by the app. There is no running sync engine, remote Room publication, working account/Google UI, durable
sign-out flow, or background scheduler. See [architecture](architecture.md) and [sync implementation](sync/implementation.md).

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
This documentation-only reorganization adds no build, test, device, or backend pass.
