# Android documentation — start here

> **Replace current state; append only to historical evidence.**
> Active documentation is an operating guide, not a transcript or delivery journal.

## Documentation rules

1. **One home per fact.** Update the owning document below; link to it elsewhere instead of copying its narrative.
2. **Keep current, planned, and historical separate.** Implemented does not mean activated; approved design does not
   mean implemented; task acceptance does not authorize the next task.
3. **Read progressively.** Start with the pickup route, then read only the references needed for the authorized task.
   Do not recursively ingest `docs/` or use the archive as routine orientation.
4. **Replace, do not prepend.** Rewrite stale status and remove superseded instructions. Do not accumulate “the owner
   subsequently…” paragraphs or explanations that earlier current-state paragraphs are historical.
5. **Preserve decisions, not session noise.** Keep invariants, design rationale, unresolved risks, and reproducible
   verification procedures. Leave pane IDs, intermediate fixes, temporary paths, old dirty-tree inventories, and commit
   receipts out of active docs. Discover live repository/agent state at pickup.
6. **Evidence is scoped and dated.** Record actual commands, results, environment, and unverified gates in one acceptance
   review. Never present an old/cached/filtered run as a new/full pass or copy test-count progressions into every document.
7. **Close tasks deliberately.** Extract durable implementation/design facts and unresolved obligations before archiving
   the completed specification/review. Keep incomplete and deferred slices in the roadmap. Do not update old records to
   pretend they describe today.
8. **Validate documentation changes.** Check relative links/anchors, new-file whitespace, and `git diff --check`; preserve
   unrelated changes. Documentation cleanup does not authorize code, configuration, deployment, commits, or pushes.

## Reading routes

- **New coordinator:** root [AGENTS.md](../AGENTS.md) → [current handoff](coordinator-handoff.md) →
  [roadmap summary](remaining-work-roadmap.md#status-and-dependencies). Read [workflow](coordinator-workflow.md) before
  delegating; follow task-specific references after scope is authorized.
- **Feature/persistence work:** [architecture](architecture.md), relevant shared
  [product behavior](../shared-docs/docs/product-behavior.md), and [testing strategy](testing-strategy.md).
- **Sync work:** [implemented sync components](sync/implementation.md), relevant sections of the approved
  [integration design](sync/integration-design.md), and the full linked
  [backend contract](../shared-docs/docs/backend-contract/README.md) as required by AGENTS.md.
- **Historical investigation only:** [archive index](archive/README.md). No archived instruction grants current authority.

## Single sources of truth

| Question | Owning document |
|---|---|
| What should a coordinator pick up, and what blocks it? | [Coordinator handoff](coordinator-handoff.md) |
| How do coordinator, Worker, and QA operate? | [Coordinator workflow](coordinator-workflow.md) |
| What is implemented, and where are its boundaries? | [Architecture](architecture.md) and [sync implementation](sync/implementation.md) |
| What Android integration decisions are approved but not yet delivered? | [Integration design](sync/integration-design.md) |
| What remains, in what order, and for which milestone? | [Roadmap](remaining-work-roadmap.md) |
| How should changes be verified? | [Testing strategy](testing-strategy.md) |
| What exactly is the current assignment? | Its specification under [remaining-work/](remaining-work/README.md) |
| What passed at a completed gate? | Its acceptance review, indexed in the [archive](archive/README.md) |

Android code is the authority for implemented behavior. The shared contract is the authority for finalized cross-project
requirements. This folder owns Android implementation, tests, and delivery planning; `shared-docs/` owns shared product,
API/sync contracts, and the [fixed catalogue package](../shared-docs/catalogue/README.md). Do not duplicate those contracts
here. Update only documents whose facts changed, not every document at every gate.
