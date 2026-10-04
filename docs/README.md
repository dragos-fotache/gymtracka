# GymTracka Android documentation

This directory belongs to the Android repository. It contains Android-only implementation, test, planning and review
records. The sibling `shared-docs/` checkout contains cross-project product behavior, backend/API contracts and the
fixed catalogue package; the backend must not depend on these Android-local documents.

## Current documents

- [Architecture](architecture.md) — app structure, Room schema/migrations, repository/state boundaries and persistence
  invariants.
- [Testing strategy](testing-strategy.md) — coverage, executed gates, limitations and verification commands.
- [Coordinator handoff](coordinator-handoff.md) — current baseline, scope and the next pickup boundary.
- [Remaining-work roadmap](remaining-work-roadmap.md) — R01–R18 dependencies, personal-use priorities, deferred public
  readiness, acceptance outcomes and per-task planning workflow.
- [Sync implementation plan](sync-implementation-plan.md) — capability definitions, invariants and historical review
  ledger. Its archived execution instructions do not override the current handoff/roadmap.
- [Simulator retirement review](fake-retirement-review.md) and [coverage transfer ledger](fake-retirement-coverage.md)
  — evidence for replacing the backend-sized fake with isolated real-HTTP tests.
- [Historical sync assignments](sync-assignments/) — preserved task, contributor-plan and review records. Assignment
  018 remains paused; do not recreate the simulator.

Detailed task/subtask plans and acceptance reviews belong under `docs/remaining-work/` in this repository,
created on pickup. [R01 audit](remaining-work/R01-task.md), [design](remaining-work/R01-design.md) and
[verification record](remaining-work/R01-review.md) record owner design approval on 2026-10-03, along with the next
[R02a shared specification](remaining-work/R02a-task.md). R02a–d are now accepted; see the
[R02b review](remaining-work/R02b-review.md), [R02c review](remaining-work/R02c-review.md),
[R02d review](remaining-work/R02d-review.md) and
[authorized Android delivery commit record](remaining-work/R02bc-commit-review.md). Live R03c pickup found R02d/R03a/b
included in clean Android `6134a58`; their earlier uncommitted descriptions are historical.
The owner's requested [R03 subtask split](remaining-work/R03-task.md) defines secure transport, sync, auth and deferred
lifecycle/streaming gates. The owner subsequently authorized **R03a only**, delivered under the
[detailed shared specification](remaining-work/R03a-task.md), now accepted; see the
[R03a review](remaining-work/R03a-review.md). It supplies reusable secure HTTP I/O, not endpoint adapters or activation.
R03b is also accepted; see its [specification](remaining-work/R03b-task.md) and [review](remaining-work/R03b-review.md).
It adds the unused production sync adapter/shared errors with real-service interoperability through test TLS.
The owner subsequently authorized R03c: see its [specification](remaining-work/R03c-task.md) and
[acceptance review](remaining-work/R03c-review.md). Client gates pass (553 offline JVM/six API37 smoke/build/lint);
after separate backend correction, unchanged auth10/10 and broad56/56 pass without decoder/test changes. R03c is accepted
with historical incompatible accepted replay explicitly preserved/not counted repaired.
The owner subsequently authorized **R04a only**: [R04 split](remaining-work/R04-task.md),
[specification](remaining-work/R04a-task.md) and [review](remaining-work/R04a-review.md) record accepted UNUSED encrypted
vault/storage,627 offline JVM/27 API37 vault tests and build/lint/security/preservation checks. No refresh/startup/account
or Room activation flow. R04b is next only after specific authorization; c/R03d remain deferred/unauthorized.
No additional implementation is authorized by the roadmap or R01 plan. The near-term target is the owner's personal app
against an always-on laptop-hosted service, not a public launch.

## Shared references

- [Product behavior](../shared-docs/docs/product-behavior.md).
- [Backend/sync contract](../shared-docs/docs/backend-contract/README.md), including its linked wire/domain/authentication
  requirements.
- [Fixed catalogue package](../shared-docs/catalogue/README.md).

Update Android implementation/tests/plans here; update cross-project behavior and compatibility contracts in
`shared-docs/`. Keep both git diffs in view, preserve existing uncommitted changes, and do not claim old verification
as a fresh pass.
