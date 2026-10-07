# Historical Android records — not orientation

> **Do not ingest this directory during normal coordinator pickup.** Start with [current documentation](../README.md).
> These are frozen task-era specifications, proposals, and evidence—not current instructions or authorization.

Old “current”, “next”, “uncommitted”, “running”, model/pane identities, source paths, permissions, and test totals describe
their original gates. Do not execute commands, resume assignments, or infer live resource ownership from them. In particular,
the simulator was retired; its prepared assignment 018 is not an outstanding prerequisite. Current statuses/dependencies
live only in [the roadmap](../remaining-work-roadmap.md); live state must be rediscovered.

Historical text is retained for targeted investigation. Relative links were rebased during relocation; links into active
documents may now describe a later state. Git history retains the former entry-point narratives and delivery receipts.
Do not keep appending current pickup updates to these records. Correct factual evidence only explicitly, preserving what
was actually tested. A later regression or limitation belongs in current guidance plus its new task record.

## Accepted R-series records

Read a specification only for the precise historical API/acceptance contract; read a review for what was actually verified.
Enduring Android design is maintained in [the active integration design](../sync/integration-design.md), not by reopening R01.

| Slice | Specification / planning | Evidence |
|---|---|---|
| R01 design | [Audit](remaining-work/R01-task.md), [original design](remaining-work/R01-design.md) | [Approval](remaining-work/R01-review.md) |
| R02a raw member uniqueness | [Spec](remaining-work/R02a-task.md) | [Review](remaining-work/R02a-review.md) |
| R02b family identity | [Spec](remaining-work/R02b-task.md) | [Review](remaining-work/R02b-review.md) |
| R02c failure distinctions | [Spec](remaining-work/R02c-task.md) | [Review](remaining-work/R02c-review.md) |
| R02d auth/exact-body models | [Spec](remaining-work/R02d-task.md) | [Review](remaining-work/R02d-review.md) |
| R03a secure HTTP primitive | [Spec](remaining-work/R03a-task.md) | [Review](remaining-work/R03a-review.md) |
| R03b sync HTTP | [Spec](remaining-work/R03b-task.md) | [Review](remaining-work/R03b-review.md) |
| R03c auth HTTP | [Spec](remaining-work/R03c-task.md) | [Review / precision limitation](remaining-work/R03c-review.md) |
| R04a encrypted vault | [Spec](remaining-work/R04a-task.md) | [Review](remaining-work/R04a-review.md) |
| R04b refresh/leases | [Spec](remaining-work/R04b-task.md) | [Review](remaining-work/R04b-review.md) |
| R04c startup/recovery seams | [Spec](remaining-work/R04c-task.md) | [Review](remaining-work/R04c-review.md) |
| R05a canonical storage/watermarks | [Spec](remaining-work/R05a-task.md) | [Review](remaining-work/R05a-review.md) |
| R05b fenced canonical batches | [Spec](remaining-work/R05b-task.md) | [Review](remaining-work/R05b-review.md) |
| R05c protected publication | [Spec](remaining-work/R05c-task.md) | [Review](remaining-work/R05c-review.md) |
| R06a durable head claiming/replay | [Spec](remaining-work/R06a-task.md) | [Review](remaining-work/R06a-review.md) |
| R06b single-head outcomes/retry | [Spec](remaining-work/R06b-task.md) | [Review](remaining-work/R06b-review.md) |
| R06c atomic canonical acknowledgment | [Spec](remaining-work/R06c-task.md) | [Review](remaining-work/R06c-review.md) |
| R06d bounded durable push delivery | [Spec](remaining-work/R06d-task.md) | [Review](remaining-work/R06d-review.md) |
| R07a incremental checkpoint transactions | [Spec](remaining-work/R07a-task.md) | [Review](remaining-work/R07a-review.md) |
| R07b bounded incremental HTTP pull | [Spec](remaining-work/R07b-task.md) | [Review](remaining-work/R07b-review.md) |
| R07c shared push-before-pull / valid tail | [Spec](remaining-work/R07c-task.md) | [Review](remaining-work/R07c-review.md) |
| R08a durable snapshot staging storage | [Spec](remaining-work/R08a-task.md) | [Review](remaining-work/R08a-review.md) |
| R08b bounded frozen-snapshot HTTP download | [Spec](remaining-work/R08b-task.md) | [Review](remaining-work/R08b-review.md) |
| R08c complete canonical baseline/absence/publication recovery | [Spec](remaining-work/R08c-task.md) | [Review](remaining-work/R08c-review.md) |
| R09a durable private account authority/preparation journal | [Spec](remaining-work/R09a-task.md) | [Review](remaining-work/R09a-review.md) |
| R09b prepared vault/Room coordination/crash recovery | [Spec](remaining-work/R09b-task.md) | [Review](remaining-work/R09b-review.md) |
| R09c private prepared engine admission/canonical bootstrap | [Spec](remaining-work/R09c-task.md) | [Review](remaining-work/R09c-review.md) |
| R09d private completed-preparation/vault promotion | [Spec](remaining-work/R09d-task.md) | [Review](remaining-work/R09d-review.md) |
| R09e inert settled occupied-credential reauthentication | [Spec](remaining-work/R09e-task.md) | [Review](remaining-work/R09e-review.md) |
| R09f inert exact-pending-refresh reconciliation | [Spec](remaining-work/R09f-task.md) | [Review](remaining-work/R09f-review.md) |
| R09g inert complete same-account recovery/superseded evidence | [Spec](remaining-work/R09g-task.md) | [Review](remaining-work/R09g-review.md) |
| R12a real finite-edit admission and timer barriers | [Spec](remaining-work/R12a-task.md) | [Review](remaining-work/R12a-review.md) |

[R03 split](remaining-work/R03-task.md) and [R04 split](remaining-work/R04-task.md) are historical planning records;
outstanding lifecycle and durable account-integration requirements remain in the active roadmap. The
[R02b/c delivery record](remaining-work/R02bc-commit-review.md) is a historical commit-scope receipt, not new permission.

## Earlier foundations and retired simulator

- [Old M-series implementation plan and review ledger](sync-implementation-plan.md): M0–M3 foundation evidence and
  superseded execution plans. Outstanding M5–M12 capabilities are represented by the active R-series roadmap.
- [Testing history](testing-history.md): pre-reorganization test-layer records, including early app/Compose/CI gates.
- [Simulator retirement review](fake-retirement-review.md) and [coverage transfer map](fake-retirement-coverage.md):
  why removed fake tests were replaced by isolated HTTP coverage or intentionally not transferred.
- Assignment 016: [specification](sync-assignments/016-task.md), [contributor plan](sync-assignments/016-muse-plan.md),
  [review](sync-assignments/016-review.md).
- Assignment 017: [specification](sync-assignments/017-task.md), [review](sync-assignments/017-review.md).
- Assignment 018: [unimplemented proposal](sync-assignments/018-task.md), [unimplemented contributor plan](sync-assignments/018-muse-plan.md).

Neither archiving nor historic acceptance weakens present regression requirements. Use current testing strategy and
code to determine today's suite; historical reported counts are not a freshness guarantee.
