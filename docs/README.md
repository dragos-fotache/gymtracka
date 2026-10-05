# Android documentation — start here

> **Replace current state; append only to historical evidence.**
> Active documentation is an operating guide, not a transcript or delivery journal.

## Documentation rules

1. **One home per fact.** Update the owning document below; link to it elsewhere instead of copying its narrative.
2. **Keep current, planned, and historical separate.** Implemented does not mean activated; approved design does not
   mean implemented; acceptance evidence alone is not authorization. Automatic next-slice work and scoped acceptance
   commits follow the owner's separate [standing workflow rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
3. **Read progressively.** Follow the [staged reading policy](#progressive-reading-and-context-discipline).
   Do not recursively ingest `docs/`, the shared contract map or the archive as routine orientation.
4. **Replace, do not prepend.** Rewrite stale status and remove superseded instructions. Do not accumulate “the owner
   subsequently…” paragraphs or explanations that earlier current-state paragraphs are historical.
5. **Preserve decisions, not session noise.** Keep invariants, design rationale, unresolved risks, and reproducible
   verification procedures. Leave pane IDs, intermediate fixes, temporary paths, old dirty-tree inventories, and commit
   receipts out of active docs; pass accepted commit hashes in the successor prompt instead. Discover live
   repository/agent state at pickup.
6. **Evidence is scoped and dated.** Record actual commands, results, environment, and unverified gates in one acceptance
   review. Never present an old/cached/filtered run as a new/full pass or copy test-count progressions into every document.
7. **Close tasks deliberately.** Extract durable implementation/design facts and unresolved obligations before archiving
   the completed specification/review. Keep incomplete and deferred slices in the roadmap. Do not update old records to
   pretend they describe today.
8. **Validate documentation changes.** Check relative links/anchors, new-file whitespace, and `git diff --check`; preserve
   unrelated changes. Documentation maintenance alone does not authorize implementation or pane rotation. The
   [slice workflow](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation) owns scoped commit/continuation
   permission; configuration, deployment and pushes still need separate authorization.

## Progressive reading and context discipline

Read to answer the **next concrete question**, not to exhaust the documentation graph. A link is a dependency pointer,
not an instruction to recursively ingest everything behind it. Documents already supplied in context need not be reread
unless their live contents changed or an exact passage is needed.

1. **Pickup/readiness:** use the supplied agent guide, this policy, the current handoff, roadmap status/dependencies and
   workflow authority/transfer rules. Verify live trees/indexes/untracked files/HEADs, roles/models and relevant resource
   ownership. Readiness means ownership, bounded scope, dependencies, required gates and known blockers are understood;
   it does not require a full architecture/contract/code audit or a settled implementation specification.
2. **Design after release:** read the authoritative architecture/product/design sections, testing policy and concrete
   source/API/harness seams needed to settle the slice. For sync, read the applicable contract sections and referenced
   wire schemas/types before specifying behavior. Follow further references when a dependency or safety invariant needs
   them; read a whole document when the task genuinely requires its complete context. Do not dump unrelated OpenAPI
   routes, auth/lifecycle/export/deletion rules, catalogue data or historical reviews by default.
3. **Implementation/review:** expand reading as concrete questions arise. Reuse verified facts with source paths/anchors;
   do not repeat broad orientation between phases. A summary is a navigation aid, not a replacement for authoritative
   requirements, live code checks or independent review.
4. **Contributor packets:** provide one normative slice specification, task-relevant standards/contracts and disjoint
   role-specific source allowlists. Do not require every contributor to read the whole architecture or contract suite.
   Supply missing signatures/harness facts explicitly; narrower reading never permits opposite-role source access.

Keep tool output bounded to relevant sections, symbols, diagnostics and diffs. Avoid redundant document dumps, broad
symbol/reference results and repeated indexing retries when a bounded fallback can answer the question. Unexpected
startup context growth should trigger a review of duplicate/unrelated input, not automatic acceptance of the growth or
another exhaustive reread. A reading budget is a warning signal, **never permission to skip a required invariant or gate**.

All applicable shared-contract requirements remain binding. When a relevant requirement/dependency is unclear, expand
reading and resolve it before design/delegation; bring unresolved contradictions, missing inputs or scope questions to
the user. Progressive reading changes the order and breadth of ingestion, not contract authority, migration/security/
persistence safeguards, source independence or verification obligations.

## Reading routes

- **New coordinator:** supplied root [AGENTS.md](../AGENTS.md) → this policy →
  [current handoff](coordinator-handoff.md) → [roadmap summary](remaining-work-roadmap.md#status-and-dependencies) →
  [workflow authority/transfer rules](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
  Report bounded pickup readiness before ownership release; after release, follow only the task-specific references
  needed for design. Read the full workflow before delegation, not the full system documentation before readiness.
- **Feature/persistence work:** relevant sections of [architecture](architecture.md), shared
  [product behavior](../shared-docs/docs/product-behavior.md), and [testing strategy](testing-strategy.md), plus concrete
  code/harness seams needed for the decision.
- **Sync work:** applicable [implemented components](sync/implementation.md),
  [integration design](sync/integration-design.md), [backend contract](../shared-docs/docs/backend-contract/README.md)
  sections, referenced wire schemas/types and directly needed dependencies. The contract map is an index, not a
  mandatory full-suite startup read.
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
