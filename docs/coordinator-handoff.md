# Coordinator pickup

> **Current snapshot only. Replace state; do not append session history.** Follow [minimal successor prompts](coordinator-workflow.md#minimal-successor-prompts--owner-rule) and [progressive reading](README.md#progressive-reading-and-context-discipline).

## Current boundary and authority

**R09h — app-facing activation and offline recovery is accepted** at the owner-confirmed authority/recovery boundary. Its [review](archive/remaining-work/R09h-review.md) owns actual evidence; its [archived specification](archive/remaining-work/R09h-task.md) owns the task-era API contract. No implementation specification is open.

The owner requires stopping after R09h's scoped local commit and durable handoff preparation, **without creating a successor coordinator**. No successor has been launched. Next eligible integration is **R12c — scoped app and timer reconciliation / package closure**, with a fresh coordinator when the owner resumes/releases pickup. Follow [workflow](coordinator-workflow.md) and the finite [three-slice R12 plan](remaining-work-roadmap.md#r12--feature-and-app-reconciliation); no additional R09/R12 slices or deferred public work are inferred.

R12c's R09h authority, R12a/b and Core R10a dependencies are delivered. Scope/privacy, real timer-impact/publication and actual app/Compose/package gates remain binding. Missing safe inputs block affected integration, not permission for no-op callbacks. Product/destructive/configuration, legacy-data and unresolved scope questions go to the owner.

## Implemented boundary and outstanding obligations

Production startup remains **signed-out/local-only**, with no publisher/engine/account scope constructed by root or leaf factories. The explicit [app-facing boundary](sync/implementation.md#app-facing-authority-and-offline-recovery) composes private grant preparation, actual bootstrap/tail/completion, vault promotion, matching offline recovery and bounded reconnect. It requires caller-supplied transition/publication machinery, exposes process-fenced account repositories, rejects stale/foreign grants and preserves exact pending intent/uncertain evidence. Its delivery is not live screen activation.

The finite R09a–h authority/recovery plan is closed; general switching/sign-out/import-replace UI remains deferred. R12c consumes this boundary for scoped repository/state rebinding, navigation/active pill and canonical timer-impact selection. Remote finish/delete/replacement/completion invalidation must claim/drain rest while the old set exists, recheck outbox protection and preserve valid unchanged timers. ACTIVE projection/full-resync orchestration, typed cursor/stage/rejection recovery and complete private-scope/real Compose package gates remain integration obligations; R09h reconnect does not publish or silently restart/discard.

**Legacy local-only data disposition is unresolved.** Activation encountering it refuses unchanged. No import, deletion, reassignment or clean/cloud-only choice is authorized; obtain the owner's decision and bounded scope before affected activation. [P16/P17](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions) remain binding. Superseded uncertain archives stay encrypted/private/preserved and permanently excluded from credential selection/replay/cleanup. Foreign/unreadable/unknown-provenance evidence is not adoptable. The [legacy accepted auth-replay limitation](sync/implementation.md#legacy-auth-replay-limitation) remains unrepaired.

[Core R10a](archive/remaining-work/R10a-review.md) is accepted representative real Room/HTTP convergence evidence, not real process death, retention pruning, all R10b conflicts, deployed TLS or real Google/phone/personal data. R10b remains deferred public. R11 identity, R13 account UI/foreground triggers and R17 deployment/live journeys remain separate milestones. External connectivity/OAuth/signing/account inputs are not inferred from synthetic tests.

## Pickup and resource safeguards

Supplied AGENTS → README policy → this handoff → roadmap status/dependencies → workflow authority/transfer rules. Rediscover BOTH live trees/indexes/all untracked/HEADs, roles/models and resource ownership; preserve user changes. Report bounded readiness directly/asynchronously to outgoing live `gym-coordinator` and wait for verified release before specification, edits, delegation or builds. Read concrete dependencies after release and the full workflow before delegation.

Fresh native coordinator: `openai-codex/gpt-6.1-sol`/high. Fresh Worker/QA: `meta/muse-spark-1.3-contributor`/high with disjoint production/test source. Completed conversations do not transfer. Reports use fire-and-forget prompts, never `--wait`, agent wait or acknowledgment polling. Every Gradle needs an explicit serialized slot; lint freezes both roles' source.

R09h build slots and test-owned emulator/ADB/Android/AVD homes are released. No service, relay, device, resource or contributor session transfers. Rediscover live processes; persistent Gradle/Kotlin daemons are not a granted build slot. No pushes, deployment, private configuration, real Google/account/device or personal-data actions without separate permission. Backend access remains operational build/start and black-box HTTP only under the [isolated procedure](testing-strategy.md#isolated-real-backend-procedure), never source/private-config review.
